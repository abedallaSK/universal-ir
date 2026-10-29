package com.universalir.ir.format

import com.universalir.ir.core.PatternValidator
import com.universalir.ir.core.TimingBuilder
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.consumer.ConsumerProtocols
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Outcome of an import: accepted code set plus every rejected item with its reason. */
data class ImportReport(
    val codeSet: CanonicalCodeSet?,
    val accepted: Int,
    val rejected: List<Rejection>,
    val fatal: String? = null,
)

data class Rejection(val item: String, val reason: String)

data class ImportContext(
    val id: String,
    val brand: String,
    val category: DeviceCategory,
    val models: List<String> = emptyList(),
    val source: CodeSource,
)

private const val MAX_FILE_CHARS = 2_000_000
private const val MAX_COMMANDS = 1_000

private fun validRaw(cmd: CanonicalCommand): String? {
    val pattern = com.universalir.ir.core.IrPattern(cmd.carrierHz ?: 0, cmd.timings!!.toIntArray())
    val issues = PatternValidator.validate(pattern)
    return issues.firstOrNull()?.message
}

/**
 * Flipper Zero `.ir` files ("Filetype: IR signals file"). Raw signals carry their own carrier;
 * parsed signals are only accepted for protocols this app implements.
 */
object FlipperIrImporter {
    fun import(text: String, ctx: ImportContext): ImportReport {
        if (text.length > MAX_FILE_CHARS) return ImportReport(null, 0, emptyList(), "File too large")
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "Filetype: IR signals file") {
            return ImportReport(null, 0, emptyList(), "Not a Flipper IR signals file (missing header)")
        }
        val blocks = mutableListOf<MutableMap<String, String>>()
        for (line in lines.drop(1)) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val idx = t.indexOf(':')
            if (idx <= 0) continue
            val key = t.substring(0, idx).trim()
            val value = t.substring(idx + 1).trim()
            if (key == "name") blocks += mutableMapOf("name" to value)
            else blocks.lastOrNull()?.put(key, value)
        }
        val commands = mutableListOf<CanonicalCommand>()
        val rejected = mutableListOf<Rejection>()
        for (b in blocks) {
            val name = b["name"] ?: continue
            if (commands.size >= MAX_COMMANDS) { rejected += Rejection(name, "Command limit reached"); continue }
            when (b["type"]) {
                "raw" -> {
                    val freq = b["frequency"]?.toIntOrNull()
                    val data = b["data"]?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.map { it.toIntOrNull() }
                    when {
                        freq == null -> rejected += Rejection(name, "Missing frequency")
                        data.isNullOrEmpty() || data.any { it == null || it <= 0 } -> rejected += Rejection(name, "Invalid raw data")
                        else -> {
                            val cmd = CanonicalCommand(CommandNames.normalise(name), name, CommandKind.RAW, freq, data.map { it!! })
                            val err = validRaw(cmd)
                            if (err != null) rejected += Rejection(name, err) else commands += cmd
                        }
                    }
                }
                "parsed" -> {
                    val proto = b["protocol"]
                    val p = proto?.let { ConsumerProtocols.byId(it) }
                    val addr = b["address"]?.let(::leHex)
                    val cmdv = b["command"]?.let(::leHex)
                    when {
                        p == null -> rejected += Rejection(name, "Protocol '$proto' not implemented")
                        addr == null || cmdv == null -> rejected += Rejection(name, "Bad address/command")
                        p.checkRange(addr, cmdv).isNotEmpty() -> rejected += Rejection(name, p.checkRange(addr, cmdv).joinToString())
                        else -> commands += CanonicalCommand(CommandNames.normalise(name), name, CommandKind.PARSED, protocol = p.info.id, address = addr, command = cmdv)
                    }
                }
                else -> rejected += Rejection(name, "Unknown signal type '${b["type"]}'")
            }
        }
        if (commands.isEmpty()) return ImportReport(null, 0, rejected, "No usable signals in file")
        val protocolId = commands.map { it.protocol ?: "raw" }.distinct().singleOrNull() ?: "mixed"
        val set = CanonicalCodeSet(
            id = ctx.id, protocolId = protocolId, category = ctx.category, brand = ctx.brand, models = ctx.models,
            commands = commands, source = ctx.source,
        )
        return ImportReport(set, commands.size, rejected)
    }

    /** Flipper writes multi-byte values as little-endian hex bytes, e.g. "07 00 00 00". */
    private fun leHex(s: String): Long? {
        val parts = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 8) return null
        var v = 0L
        parts.forEachIndexed { i, p -> v = v or ((p.toIntOrNull(16) ?: return null).toLong() shl (8 * i)) }
        return v
    }
}

/**
 * IRDB CSV (`functionname,protocol,device,subdevice,function`). Only protocols with an
 * implemented encoder are accepted; everything else is reported, never guessed.
 */
object IrdbCsvImporter {
    fun import(text: String, ctx: ImportContext): ImportReport {
        if (text.length > MAX_FILE_CHARS) return ImportReport(null, 0, emptyList(), "File too large")
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty() || !lines.first().trim().lowercase().startsWith("functionname,protocol,device,subdevice,function")) {
            return ImportReport(null, 0, emptyList(), "Not an IRDB CSV (unexpected header)")
        }
        val commands = mutableListOf<CanonicalCommand>()
        val rejected = mutableListOf<Rejection>()
        for (line in lines.drop(1)) {
            val cols = parseCsvLine(line)
            if (cols.size != 5) { rejected += Rejection(line, "Expected 5 columns"); continue }
            val (fname, proto, dev, sub, fn) = cols
            val d = dev.toLongOrNull(); val s = sub.toLongOrNull(); val f = fn.toLongOrNull()
            if (d == null || s == null || f == null) { rejected += Rejection(fname, "Non-numeric device/subdevice/function"); continue }
            val mapped = mapIrdb(proto, d, s, f)
            if (mapped == null) { rejected += Rejection(fname, "IRDB protocol '$proto' not implemented"); continue }
            val (pid, addr, cmd) = mapped
            val p = ConsumerProtocols.byId(pid)!!
            val range = p.checkRange(addr, cmd)
            if (range.isNotEmpty()) { rejected += Rejection(fname, range.joinToString()); continue }
            if (commands.size >= MAX_COMMANDS) { rejected += Rejection(fname, "Command limit reached"); continue }
            commands += CanonicalCommand(CommandNames.normalise(fname), fname, CommandKind.PARSED, protocol = pid, address = addr, command = cmd)
        }
        if (commands.isEmpty()) return ImportReport(null, 0, rejected, "No supported codes in file")
        val set = CanonicalCodeSet(
            id = ctx.id, protocolId = commands.map { it.protocol!! }.distinct().singleOrNull() ?: "mixed",
            category = ctx.category, brand = ctx.brand, models = ctx.models, commands = commands, source = ctx.source,
        )
        return ImportReport(set, commands.size, rejected)
    }

    /** Maps IRDB / IrpTransmogrifier protocol names to (protocol id, address, command). */
    fun mapIrdb(proto: String, device: Long, subdevice: Long, function: Long): Triple<String, Long, Long>? =
        when (proto.trim().uppercase()) {
            "NEC", "NEC1", "NEC2" ->
                if (subdevice < 0 || subdevice == (device xor 0xFF)) Triple("NEC", device, function)
                else Triple("NECext", device or (subdevice shl 8), function or ((function xor 0xFF) shl 8))
            "NECX1", "NECX2" ->
                if (subdevice < 0 || subdevice == device) Triple("Samsung32", device, function) else null
            "SONY12" -> Triple("SIRC", device, function)
            "SONY15" -> Triple("SIRC15", device, function)
            "SONY20" -> Triple("SIRC20", device or ((if (subdevice < 0) 0 else subdevice) shl 5), function)
            "RC5" -> if (function < 64) Triple("RC5", device, function) else Triple("RC5X", device, function)
            "RC6" -> Triple("RC6", device, function)
            else -> null
        }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
        }
        out += sb.toString().trim()
        return out
    }
}

/**
 * SmartIR climate JSON. Supports Broadlink base64 packets and ESPHome-style signed raw lists.
 * Commands become AC lookup keys: `off`, or `mode/fan/temp` (e.g. `cool/low/24`).
 */
object SmartIrClimateImporter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Meta(
        val manufacturer: String,
        val models: List<String>,
        val minTemp: Int,
        val maxTemp: Int,
        val modes: List<String>,
        val fanModes: List<String>,
        val controller: String,
        val encoding: String,
    )

    fun readMeta(root: JsonObject): Meta = Meta(
        manufacturer = root["manufacturer"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
        models = root["supportedModels"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
        minTemp = root["minTemperature"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 16,
        maxTemp = root["maxTemperature"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 30,
        modes = root["operationModes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
        fanModes = root["fanModes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
        controller = root["supportedController"]?.jsonPrimitive?.contentOrNull ?: "",
        encoding = root["commandsEncoding"]?.jsonPrimitive?.contentOrNull ?: "",
    )

    fun import(text: String, ctx: ImportContext): ImportReport {
        if (text.length > MAX_FILE_CHARS) return ImportReport(null, 0, emptyList(), "File too large")
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return ImportReport(null, 0, emptyList(), "Invalid JSON: ${e.message?.take(120)}")
        }
        val meta = readMeta(root)
        val decoder: (JsonElement) -> DecodeResult<com.universalir.ir.core.IrPattern> = when {
            meta.controller == "Broadlink" && meta.encoding.equals("Base64", true) -> { e ->
                val s = (e as? JsonPrimitive)?.contentOrNull
                if (s == null) DecodeResult.Error("Expected a base64 string") else BroadlinkCodec.decodeBase64(s)
            }
            meta.encoding.equals("Raw", true) -> { e ->
                when (e) {
                    is JsonPrimitive -> RawTimingsParser.parse(e.content, 38_000)
                    else -> runCatching { RawTimingsParser.parse(e.jsonArray.joinToString(" ") { it.jsonPrimitive.content }, 38_000) }
                        .getOrElse { DecodeResult.Error("Unsupported raw layout") }
                }
            }
            else -> return ImportReport(null, 0, emptyList(), "Unsupported SmartIR controller/encoding '${meta.controller}/${meta.encoding}'")
        }
        val commandsObj = root["commands"]?.jsonObject ?: return ImportReport(null, 0, emptyList(), "Missing 'commands'")
        val commands = mutableListOf<CanonicalCommand>()
        val rejected = mutableListOf<Rejection>()
        fun add(key: String, e: JsonElement) {
            when (val r = decoder(e)) {
                is DecodeResult.Error -> rejected += Rejection(key, r.reason)
                is DecodeResult.Ok -> {
                    val issues = PatternValidator.validate(r.value)
                    if (issues.isNotEmpty()) rejected += Rejection(key, issues.first().message)
                    else if (commands.size < MAX_COMMANDS * 2) commands += CanonicalCommand(
                        key, key, CommandKind.RAW, r.value.carrierHz, r.value.timings().toList(), r.value.frameStarts,
                    )
                }
            }
        }
        for ((mode, v) in commandsObj) {
            if (v is JsonPrimitive) { add(normaliseMode(mode), v); continue }
            val fans = (v as? JsonObject) ?: continue
            for ((fan, temps) in fans) {
                val tObj = (temps as? JsonObject)?.let { firstSwingLevel(it) }
                if (tObj == null) { add("${normaliseMode(mode)}/${normaliseFan(fan)}", temps); continue }
                for ((t, code) in tObj) {
                    // Swing-aware files nest one more level; take the first variant per temperature.
                    val actual = (code as? JsonObject)?.values?.firstOrNull() ?: code
                    val temp = t.toDoubleOrNull()?.toInt()
                    if (temp == null) { rejected += Rejection("$mode/$fan/$t", "Non-numeric temperature"); continue }
                    add("${normaliseMode(mode)}/${normaliseFan(fan)}/$temp", actual)
                }
            }
        }
        if (commands.isEmpty()) return ImportReport(null, 0, rejected, "No usable commands")
        val set = CanonicalCodeSet(
            id = ctx.id, protocolId = PROTOCOL_ID, category = DeviceCategory.AIR_CONDITIONER,
            brand = ctx.brand, models = ctx.models.ifEmpty { meta.models }, commands = commands, source = ctx.source,
            notes = listOf(
                "Captured remote codes (lookup table), ${meta.minTemp}-${meta.maxTemp} °C, modes ${meta.modes.joinToString()}, fans ${meta.fanModes.joinToString()}.",
                "Broadlink packets do not record the carrier; 38 kHz is assumed.",
            ),
        )
        return ImportReport(set, commands.size, rejected)
    }

    /**
     * Most swing-aware SmartIR files nest swing *between* fan and temperature
     * (`mode/fan/swing/temp`). Descends into the first swing variant until temperature keys appear,
     * so these files load like the `mode/fan/temp/swing` layout handled above.
     */
    private fun firstSwingLevel(obj: JsonObject): JsonObject {
        var o = obj
        while (o.isNotEmpty() && o.keys.first().toDoubleOrNull() == null && o.values.first() is JsonObject) {
            o = o.values.first().jsonObject
        }
        return o
    }

    fun normaliseMode(m: String) = when (m.lowercase()) {
        "fan" -> "fan_only"; "heat_cool" -> "auto"; else -> m.lowercase()
    }

    fun normaliseFan(f: String) = when (f.lowercase()) {
        "mid", "med", "middle" -> "medium"; else -> f.lowercase()
    }

    const val PROTOCOL_ID = "smartir_table"
}

/** Detects the format of a user-supplied file by content, never by extension alone. */
object FormatDetector {
    enum class Kind { FLIPPER, IRDB_CSV, SMARTIR_JSON, CANONICAL_JSON, UNKNOWN }

    fun detect(text: String): Kind {
        val head = text.trimStart().take(200)
        return when {
            head.startsWith("Filetype: IR signals file") -> Kind.FLIPPER
            head.lowercase().startsWith("functionname,protocol,device,subdevice,function") -> Kind.IRDB_CSV
            head.startsWith("{") && text.contains("\"formatVersion\"") && text.contains("\"commands\"") -> Kind.CANONICAL_JSON
            head.startsWith("{") && text.contains("\"commands\"") && text.contains("\"supportedController\"") -> Kind.SMARTIR_JSON
            else -> Kind.UNKNOWN
        }
    }
}

/** Parses an exported canonical code set, re-validating every raw command. */
object CanonicalImporter {
    fun import(text: String): ImportReport {
        val set = try {
            CanonicalCodeSet.json.decodeFromString(CanonicalCodeSet.serializer(), text)
        } catch (e: Exception) {
            return ImportReport(null, 0, emptyList(), "Invalid canonical JSON: ${e.message?.take(120)}")
        }
        if (set.formatVersion > CanonicalCodeSet.FORMAT_VERSION) return ImportReport(null, 0, emptyList(), "Unsupported format version ${set.formatVersion}")
        val rejected = mutableListOf<Rejection>()
        val ok = set.commands.filter { cmd ->
            val err = when (cmd.kind) {
                CommandKind.RAW -> if (cmd.timings.isNullOrEmpty()) "No timings" else validRaw(cmd)
                CommandKind.PARSED -> ConsumerProtocols.byId(cmd.protocol ?: "")?.checkRange(cmd.address ?: -1, cmd.command ?: -1)?.firstOrNull()
                    ?: if (ConsumerProtocols.byId(cmd.protocol ?: "") == null) "Protocol not implemented" else null
            }
            if (err != null) rejected += Rejection(cmd.name, err)
            err == null
        }.take(MAX_COMMANDS)
        if (ok.isEmpty()) return ImportReport(null, 0, rejected, "No valid commands")
        return ImportReport(set.copy(commands = ok), ok.size, rejected)
    }
}

/**
 * SmartIR `media_player` and `fan` JSON. Commands are flattened to normalised button names:
 * `on`/`off`, `volumeUp`, `sources/<name>` -> `source:<name>`, fan speeds -> `speed:<name>`.
 */
object SmartIrDeviceImporter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val SEQUENCE_GAP_US = 50_000

    fun import(text: String, ctx: ImportContext): ImportReport {
        if (text.length > MAX_FILE_CHARS) return ImportReport(null, 0, emptyList(), "File too large")
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return ImportReport(null, 0, emptyList(), "Invalid JSON: ${e.message?.take(120)}")
        }
        val meta = SmartIrClimateImporter.readMeta(root)
        val decode: (String) -> DecodeResult<com.universalir.ir.core.IrPattern> = when {
            meta.encoding.equals("Base64", true) -> BroadlinkCodec::decodeBase64
            meta.encoding.equals("Pronto", true) -> ProntoCodec::decode
            meta.encoding.equals("Raw", true) -> { s -> RawTimingsParser.parse(s, 38_000) }
            else -> return ImportReport(null, 0, emptyList(), "Unsupported encoding '${meta.encoding}'")
        }
        val commandsObj = root["commands"]?.jsonObject ?: return ImportReport(null, 0, emptyList(), "Missing 'commands'")
        val commands = mutableListOf<CanonicalCommand>()
        val rejected = mutableListOf<Rejection>()
        /** A JSON list of codes (SmartIR lights use it for "off") is sent as one pattern, 50 ms apart. */
        fun decodeElement(e: JsonElement): DecodeResult<com.universalir.ir.core.IrPattern> {
            (e as? JsonPrimitive)?.contentOrNull?.let { return decode(it) }
            val parts = runCatching { e.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
            if (parts.isNullOrEmpty() || parts.size > 4) return DecodeResult.Error("Expected a string code")
            val b = TimingBuilder()
            var carrier = 38_000
            for ((i, p) in parts.withIndex()) {
                val one = when (val r = decode(p)) {
                    is DecodeResult.Error -> return DecodeResult.Error("Part ${i + 1}: ${r.reason}")
                    is DecodeResult.Ok -> r.value
                }
                carrier = one.carrierHz
                if (i > 0) b.space(SEQUENCE_GAP_US)
                b.beginFrame()
                one.timings().forEachIndexed { k, t -> if (k % 2 == 0) b.mark(t) else b.space(t) }
            }
            return DecodeResult.Ok(b.build(carrier))
        }
        fun add(name: String, label: String, e: JsonElement) {
            when (val r = decodeElement(e)) {
                is DecodeResult.Error -> rejected += Rejection(label, r.reason)
                is DecodeResult.Ok -> {
                    val issue = PatternValidator.validate(r.value).firstOrNull()
                    if (issue != null) rejected += Rejection(label, issue.message)
                    else if (commands.size < MAX_COMMANDS) commands += CanonicalCommand(name, label, CommandKind.RAW, r.value.carrierHz, r.value.timings().toList(), r.value.frameStarts)
                }
            }
        }
        for ((key, value) in commandsObj) {
            when {
                value is JsonObject && key == "sources" -> value.forEach { (src, code) ->
                    val digit = Regex("(?i)channel\\s*(\\d)").matchEntire(src.trim())?.groupValues?.get(1)
                    if (digit != null) add("digit_$digit", src, code) else add("source:${src.trim()}", src, code)
                }
                value is JsonObject -> value.forEach { (speed, code) ->
                    val prefix = if (key == "reverse") "reverse" else "speed"
                    add("$prefix:$speed", "$key $speed", code)
                }
                else -> add(mapName(key), key, value)
            }
        }
        if (commands.isEmpty()) return ImportReport(null, 0, rejected, "No usable commands")
        val set = CanonicalCodeSet(
            id = ctx.id, protocolId = "raw", category = ctx.category, brand = ctx.brand,
            models = ctx.models.ifEmpty { meta.models.filter { it.isNotBlank() } }, commands = commands, source = ctx.source,
            notes = listOf("Captured remote codes. Broadlink packets do not record the carrier; 38 kHz is assumed."),
        )
        return ImportReport(set, commands.size, rejected)
    }

    private fun mapName(key: String) = when (key) {
        "on" -> CommandNames.POWER_ON
        "off" -> CommandNames.POWER_OFF
        "volumeUp" -> CommandNames.VOL_UP
        "volumeDown" -> CommandNames.VOL_DOWN
        "mute" -> CommandNames.MUTE
        "nextChannel" -> CommandNames.CH_UP
        "previousChannel" -> CommandNames.CH_DOWN
        "oscillate" -> CommandNames.OSCILLATE
        // SmartIR light files.
        "brighten" -> CommandNames.BRIGHTNESS_UP
        "dim" -> CommandNames.BRIGHTNESS_DOWN
        "colder" -> CommandNames.COLOR_TEMP_COOL
        "warmer" -> CommandNames.COLOR_TEMP_WARM
        "night" -> CommandNames.NIGHT_LIGHT
        else -> CommandNames.normalise(key)
    }
}

