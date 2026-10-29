package com.universalir.data.wifi

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private val cloudJson = Json { ignoreUnknownKeys = true }
private fun parseJson(text: String): JsonObject? = runCatching { cloudJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
private fun JsonObject.o(k: String) = this[k] as? JsonObject
private fun JsonObject.p(k: String) = this[k] as? JsonPrimitive
private fun JsonObject.s(k: String) = p(k)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

// ================================================================== Electra Smart (Israel)

/**
 * Electra Smart cloud API, as used by the open-source `electrasmart` library (Home Assistant's integration).
 * Login: phone → SMS one-time code → long-lived token; each session then gets a short-lived "sid" (15 min).
 * Pure Kotlin request builders / parsers (unit-tested); the HTTP calls live in [ElectraApi].
 */
object ElectraCodec {
    const val URL = "https://app.ecpiot.co.il/mobile/mobilecommand"
    const val USER_AGENT = "Electra Client"
    const val OS = "android"
    const val OS_VER = "M4B30Z"
    const val STATUS_OK = 0
    const val SID_TTL_MS = 900_000L
    const val SID_RETRY_GAP_MS = 300_000L
    val TEMP_RANGE = 16..30

    /** A client id in the format the reference library generates ("2b950000" + 8 digits). */
    fun newImei(random: Random = Random.Default): String = "2b950000" + random.nextInt(10_000_000, 100_000_000)

    private fun envelope(cmd: String, sid: String? = null, data: JsonObject? = null): String = buildJsonObject {
        put("pvdid", 1); put("id", 99); put("cmd", cmd)
        sid?.let { put("sid", it) }
        data?.let { put("data", it) }
    }.toString()

    fun sendOtp(imei: String, phone: String) = envelope("SEND_OTP", data = buildJsonObject { put("imei", imei); put("phone", phone) })

    fun checkOtp(imei: String, phone: String, code: String) = envelope("CHECK_OTP", data = buildJsonObject {
        put("imei", imei); put("phone", phone); put("code", code); put("os", OS); put("osver", OS_VER)
    })

    fun validateToken(imei: String, token: String) = envelope("VALIDATE_TOKEN", data = buildJsonObject {
        put("imei", imei); put("token", token); put("os", OS); put("osver", OS_VER)
    })

    fun getDevices(sid: String) = envelope("GET_DEVICES", sid)

    private fun idValue(id: String): JsonPrimitive = id.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(id)

    fun telemetry(sid: String, deviceId: String) = envelope("GET_LAST_TELEMETRY", sid, buildJsonObject {
        put("id", idValue(deviceId)); put("commandName", "OPER,DIAG_L2")
    })

    fun command(sid: String, deviceId: String, oper: JsonObject) = envelope("SEND_COMMAND", sid, buildJsonObject {
        put("id", idValue(deviceId))
        put("commandJson", buildJsonObject { put("OPER", oper) }.toString())
    })

    fun status(resp: JsonObject): Int? = resp.p("status")?.intOrNull
    fun res(resp: JsonObject): Int? = resp.o("data")?.p("res")?.intOrNull
    fun desc(resp: JsonObject): String? = resp.o("data")?.s("res_desc") ?: resp.o("data")?.s("desc")
    fun isOk(resp: JsonObject) = status(resp) == STATUS_OK && (res(resp) ?: STATUS_OK) == STATUS_OK
    fun token(resp: JsonObject): String? = resp.o("data")?.s("token")?.takeIf { it.isNotBlank() }
    fun sid(resp: JsonObject): String? = resp.o("data")?.s("sid")?.takeIf { it.isNotBlank() }

    data class Device(val id: String, val name: String, val mac: String?, val model: String?, val manufacturer: String?)

    /** Air conditioners from GET_DEVICES (`data.devices`, type "A/C"). */
    fun devices(resp: JsonObject): List<Device> = (resp.o("data")?.get("devices") as? JsonArray).orEmpty().mapNotNull { e ->
        val d = e as? JsonObject ?: return@mapNotNull null
        val type = d.s("deviceTypeName")
        if (type != null && type != "A/C") return@mapNotNull null
        val id = d.s("id") ?: return@mapNotNull null
        Device(id, d.s("name")?.takeIf { it.isNotBlank() } ?: "Electra AC", d.s("mac"), d.s("model"), d.s("manufactor"))
    }

    /** `data.commandJson.OPER` is a JSON string whose "OPER" member is the flat state map. */
    fun oper(resp: JsonObject): JsonObject? {
        val raw = resp.o("data")?.o("commandJson")?.get("OPER") ?: return null
        val obj = when (raw) {
            is JsonObject -> raw
            is JsonPrimitive -> parseJson(raw.content)
            else -> null
        } ?: return null
        return obj.o("OPER") ?: obj
    }

    /** Room temperature from DIAG_L2 (I_CALC_AT preferred over I_RAT; values >100 are ×256 encoded). */
    fun roomTemp(resp: JsonObject): Double? {
        val raw = resp.o("data")?.o("commandJson")?.get("DIAG_L2") ?: return null
        val diag = when (raw) {
            is JsonObject -> raw
            is JsonPrimitive -> parseJson(raw.content)
            else -> null
        }?.let { it.o("DIAG_L2") ?: it } ?: return null
        val v = (diag.s("I_CALC_AT") ?: diag.s("I_RAT"))?.toDoubleOrNull() ?: return null
        return if (kotlin.math.abs(v) > 100) (v.toInt() shr 8).toDouble() else v
    }

    fun mode(v: String?): WifiAcMode? = when (v) {
        "COOL" -> WifiAcMode.COOL; "HEAT" -> WifiAcMode.HEAT; "AUTO" -> WifiAcMode.AUTO; "DRY" -> WifiAcMode.DRY; "FAN" -> WifiAcMode.FAN
        else -> null
    }

    fun modeName(m: WifiAcMode) = when (m) {
        WifiAcMode.COOL -> "COOL"; WifiAcMode.HEAT -> "HEAT"; WifiAcMode.AUTO -> "AUTO"; WifiAcMode.DRY -> "DRY"; WifiAcMode.FAN -> "FAN"
    }

    fun fan(v: String?) = when (v) { "LOW" -> WifiFan.LOW; "MED" -> WifiFan.MEDIUM; "HIGH" -> WifiFan.HIGH; else -> WifiFan.AUTO }
    fun fanName(f: WifiFan) = when (f) { WifiFan.LOW -> "LOW"; WifiFan.MEDIUM -> "MED"; WifiFan.HIGH -> "HIGH"; WifiFan.AUTO -> "AUTO" }

    /** Units with TURN_ON_OFF keep power separately; others are off when AC_MODE is "STBY". */
    fun state(oper: JsonObject, lastMode: WifiAcMode? = null): WifiAcState {
        val acMode = oper.s("AC_MODE")
        val power = if (oper.containsKey("TURN_ON_OFF")) oper.s("TURN_ON_OFF") == "ON" && acMode != "STBY" else acMode != null && acMode != "STBY"
        return WifiAcState(
            power = power,
            mode = mode(acMode) ?: lastMode ?: WifiAcMode.COOL,
            targetC = oper.s("SPT")?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 24,
            fan = fan(oper.s("FANSPD")),
            swing = oper.s("VSWING") == "ON",
        )
    }

    fun caps(oper: JsonObject) = WifiAcCaps(tempRange = TEMP_RANGE, swing = oper.containsKey("VSWING"))

    /** The full OPER map to send: the last reported map with our changes applied. */
    fun applyState(oper: JsonObject, s: WifiAcState): JsonObject {
        val m = LinkedHashMap<String, JsonElement>(oper)
        if (oper.containsKey("TURN_ON_OFF")) {
            m["TURN_ON_OFF"] = JsonPrimitive(if (s.power) "ON" else "OFF")
            m["AC_MODE"] = JsonPrimitive(modeName(s.mode))
        } else {
            m["AC_MODE"] = JsonPrimitive(if (s.power) modeName(s.mode) else "STBY")
        }
        m["SPT"] = JsonPrimitive(s.targetC.coerceIn(TEMP_RANGE).toString())
        m["FANSPD"] = JsonPrimitive(fanName(s.fan))
        if (oper.containsKey("VSWING")) m["VSWING"] = JsonPrimitive(if (s.swing) "ON" else "OFF")
        if (oper.containsKey("AC_STSRC")) m["AC_STSRC"] = JsonPrimitive("WI-FI")
        return JsonObject(m)
    }
}

/** HTTP side of Electra Smart. Sessions are shared per token and renewed at most once per 5 minutes (lockout guard). */
object ElectraApi {
    private class Session(val sid: String, val expires: Long)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val lastValidate = ConcurrentHashMap<String, Long>()
    private val mutex = Mutex()

    private suspend fun call(body: String): JsonObject {
        val (code, text) = CloudHttp.send(ElectraCodec.URL, "POST", body, mapOf("user-agent" to ElectraCodec.USER_AGENT))
        if (code !in 200..299) throw WifiException(WifiError.PROTOCOL, "HTTP $code")
        return parseJson(text) ?: throw WifiException(WifiError.PROTOCOL, "bad reply")
    }

    /** Step 1 of login: Electra texts a code to [phone]. Returns false for an unknown phone number. */
    suspend fun sendOtp(imei: String, phone: String): WifiResult<Unit> = wifiCatching {
        val r = call(ElectraCodec.sendOtp(imei, phone))
        if (ElectraCodec.status(r) != ElectraCodec.STATUS_OK) throw WifiException(WifiError.PROTOCOL, ElectraCodec.desc(r))
        if (ElectraCodec.res(r) != ElectraCodec.STATUS_OK) throw WifiException(WifiError.AUTH, ElectraCodec.desc(r) ?: "phone")
    }

    /** Step 2: the SMS code → long-lived token. */
    suspend fun checkOtp(imei: String, phone: String, code: String): WifiResult<String> = wifiCatching {
        val r = call(ElectraCodec.checkOtp(imei, phone, code))
        if (!ElectraCodec.isOk(r)) throw WifiException(WifiError.AUTH, ElectraCodec.desc(r) ?: "code")
        ElectraCodec.token(r) ?: throw WifiException(WifiError.PROTOCOL, "no token")
    }

    private suspend fun sid(token: String, imei: String, force: Boolean): String = mutex.withLock {
        val now = System.currentTimeMillis()
        sessions[token]?.takeIf { !force && it.expires > now }?.let { return@withLock it.sid }
        val last = lastValidate[token]
        if (last != null && now - last < ElectraCodec.SID_RETRY_GAP_MS) {
            sessions[token]?.let { return@withLock it.sid } // too soon to ask again: reuse, never hammer the login
            throw WifiException(WifiError.AUTH, "session")
        }
        lastValidate[token] = now
        val r = call(ElectraCodec.validateToken(imei, token))
        val sid = ElectraCodec.sid(r)
        if (sid == null) {
            sessions.remove(token)
            throw WifiException(WifiError.AUTH, ElectraCodec.desc(r) ?: "token")
        }
        sessions[token] = Session(sid, now + ElectraCodec.SID_TTL_MS)
        sid
    }

    /** Sends a request needing a sid; on a non-OK status the session is renewed once and the request replayed. */
    suspend fun withSid(token: String, imei: String, build: (String) -> String): JsonObject {
        val first = call(build(sid(token, imei, force = false)))
        if (ElectraCodec.status(first) == ElectraCodec.STATUS_OK) return first
        val renewed = runCatching { sid(token, imei, force = true) }.getOrNull() ?: return first
        return call(build(renewed))
    }

    suspend fun devices(token: String, imei: String): WifiResult<List<ElectraCodec.Device>> = wifiCatching {
        val r = withSid(token, imei) { ElectraCodec.getDevices(it) }
        if (ElectraCodec.status(r) != ElectraCodec.STATUS_OK) throw WifiException(WifiError.AUTH, ElectraCodec.desc(r))
        ElectraCodec.devices(r)
    }
}

class ElectraAcController(private val token: String?, private val imei: String?, private val deviceId: String?) : WifiAcController {
    private var oper: JsonObject? = null
    private var lastMode: WifiAcMode? = null

    private fun creds(): Triple<String, String, String> =
        Triple(token ?: throw WifiException(WifiError.AUTH, "login"), imei ?: throw WifiException(WifiError.AUTH, "login"),
            deviceId ?: throw WifiException(WifiError.PROTOCOL, "no device id"))

    override suspend fun read(): WifiResult<WifiAcStatus> = wifiCatching {
        val (t, i, id) = creds()
        val r = ElectraApi.withSid(t, i) { ElectraCodec.telemetry(it, id) }
        if (ElectraCodec.status(r) != ElectraCodec.STATUS_OK) throw WifiException(WifiError.PROTOCOL, ElectraCodec.desc(r) ?: "telemetry")
        val o = ElectraCodec.oper(r) ?: throw WifiException(WifiError.PROTOCOL, "no state")
        oper = o
        val s = ElectraCodec.state(o, lastMode)
        if (s.power) lastMode = s.mode
        WifiAcStatus(s, ElectraCodec.caps(o), ElectraCodec.roomTemp(r))
    }

    override suspend fun write(state: WifiAcState): WifiResult<Unit> {
        if (oper == null) (read() as? WifiResult.Err)?.let { return it }
        return wifiCatching {
            val (t, i, id) = creds()
            val next = ElectraCodec.applyState(oper!!, state)
            val r = ElectraApi.withSid(t, i) { ElectraCodec.command(it, id, next) }
            if (!ElectraCodec.isOk(r)) throw WifiException(WifiError.PROTOCOL, ElectraCodec.desc(r) ?: "command")
            oper = next
            if (state.power) lastMode = state.mode
        }
    }
}

// ================================================================== Sensibo

/** Sensibo public REST API v2 with a personal API key the user creates at home.sensibo.com/me/api. */
object SensiboCodec {
    const val BASE = "https://home.sensibo.com/api/v2"
    const val FIELDS = "id,room,acState,measurements,remoteCapabilities"

    data class Pod(val id: String, val name: String, val acState: JsonObject, val capabilities: JsonObject?, val roomC: Double?)

    fun podsUrl(apiKey: String) = "$BASE/users/me/pods?fields=$FIELDS&apiKey=${enc(apiKey)}"
    fun podUrl(podId: String, apiKey: String) = "$BASE/pods/${enc(podId)}?fields=$FIELDS&apiKey=${enc(apiKey)}"
    fun acStatesUrl(podId: String, apiKey: String) = "$BASE/pods/${enc(podId)}/acStates?apiKey=${enc(apiKey)}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun isSuccess(resp: JsonObject) = resp.s("status") == "success"

    private fun pod(o: JsonObject): Pod? {
        val id = o.s("id") ?: return null
        return Pod(
            id = id,
            name = o.o("room")?.s("name")?.takeIf { it.isNotBlank() } ?: "Sensibo",
            acState = o.o("acState") ?: JsonObject(emptyMap()),
            capabilities = o.o("remoteCapabilities"),
            roomC = o.o("measurements")?.p("temperature")?.doubleOrNull,
        )
    }

    fun pods(resp: JsonObject): List<Pod> = (resp["result"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::pod) }
    fun singlePod(resp: JsonObject): Pod? = (resp["result"] as? JsonObject)?.let(::pod)

    fun mode(v: String?) = when (v) { "cool" -> WifiAcMode.COOL; "heat" -> WifiAcMode.HEAT; "dry" -> WifiAcMode.DRY; "fan" -> WifiAcMode.FAN; "auto" -> WifiAcMode.AUTO; else -> null }
    fun modeName(m: WifiAcMode) = when (m) { WifiAcMode.COOL -> "cool"; WifiAcMode.HEAT -> "heat"; WifiAcMode.DRY -> "dry"; WifiAcMode.FAN -> "fan"; WifiAcMode.AUTO -> "auto" }

    fun fan(v: String?) = when (v) {
        "low", "quiet", "medium_low" -> WifiFan.LOW
        "medium" -> WifiFan.MEDIUM
        "high", "strong", "medium_high" -> WifiFan.HIGH
        else -> WifiFan.AUTO
    }

    /** Picks the closest fan level the remote supports. */
    fun fanLevel(f: WifiFan, available: List<String>): String? {
        val prefs = when (f) {
            WifiFan.AUTO -> listOf("auto")
            WifiFan.LOW -> listOf("low", "quiet", "medium_low")
            WifiFan.MEDIUM -> listOf("medium", "medium_low", "medium_high")
            WifiFan.HIGH -> listOf("high", "strong", "medium_high")
        }
        if (available.isEmpty()) return prefs.first()
        return prefs.firstOrNull { it in available } ?: available.firstOrNull { it == "auto" } ?: available.first()
    }

    private fun modeCaps(caps: JsonObject?, mode: String): JsonObject? = caps?.o("modes")?.o(mode)
    private fun strings(o: JsonObject?, k: String) = (o?.get(k) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    private fun toC(v: Double, unit: String?) = if (unit == "F") Math.round((v - 32) * 5 / 9).toInt() else Math.round(v).toInt()

    fun state(p: Pod): WifiAcState {
        val a = p.acState
        val m = mode(a.s("mode")) ?: WifiAcMode.COOL
        return WifiAcState(
            power = a.p("on")?.booleanOrNull == true,
            mode = m,
            targetC = a.p("targetTemperature")?.doubleOrNull?.let { toC(it, a.s("temperatureUnit")) } ?: 24,
            fan = fan(a.s("fanLevel")),
            swing = a.s("swing").let { it != null && it != "stopped" },
        )
    }

    fun caps(p: Pod, mode: WifiAcMode): WifiAcCaps {
        val all = p.capabilities?.o("modes")?.keys.orEmpty()
        val modes = WifiAcMode.entries.filter { modeName(it) in all }.ifEmpty { WifiAcMode.entries }
        val mc = modeCaps(p.capabilities, modeName(mode))
        val temps = (mc?.o("temperatures")?.o("C")?.get("values") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        val fans = strings(mc, "fanLevels").map(::fan).distinct()
        val swing = strings(mc, "swing").any { it != "stopped" }
        return WifiAcCaps(
            modes = modes,
            fans = if (mc == null) WifiFan.entries else WifiFan.entries.filter { it in fans },
            tempRange = if (temps.isEmpty()) 16..30 else temps.min()..temps.max(),
            swing = mc == null || swing,
            tempIgnoredIn = if (mc != null && mc.o("temperatures") == null) setOf(mode) else setOf(WifiAcMode.FAN),
        )
    }

    /** Body for POST acStates: the current acState with our values; options the mode lacks are left out. */
    fun acStateBody(s: WifiAcState, p: Pod?): String {
        val mc = modeCaps(p?.capabilities, modeName(s.mode))
        return buildJsonObject {
            put("acState", buildJsonObject {
                put("on", s.power)
                put("mode", modeName(s.mode))
                if (mc == null || mc.o("temperatures") != null) { put("targetTemperature", s.targetC); put("temperatureUnit", "C") }
                fanLevel(s.fan, strings(mc, "fanLevels"))?.let { put("fanLevel", it) }
                val swings = strings(mc, "swing")
                if (mc == null || swings.any { it != "stopped" }) {
                    val on = swings.firstOrNull { it == "rangeFull" } ?: swings.firstOrNull { it != "stopped" } ?: "rangeFull"
                    put("swing", if (s.swing) on else "stopped")
                }
            })
        }.toString()
    }
}

object SensiboApi {
    private suspend fun call(url: String, method: String = "GET", body: String? = null): JsonObject {
        val (code, text) = CloudHttp.send(url, method, body)
        if (code == 401 || code == 403) throw WifiException(WifiError.AUTH, "HTTP $code")
        if (code !in 200..299) throw WifiException(WifiError.PROTOCOL, "HTTP $code")
        val r = parseJson(text) ?: throw WifiException(WifiError.PROTOCOL, "bad reply")
        if (!SensiboCodec.isSuccess(r)) throw WifiException(WifiError.PROTOCOL, r.s("reason") ?: r.s("message") ?: "failed")
        return r
    }

    suspend fun pods(apiKey: String): WifiResult<List<SensiboCodec.Pod>> = wifiCatching { SensiboCodec.pods(call(SensiboCodec.podsUrl(apiKey))) }

    suspend fun pod(apiKey: String, podId: String): SensiboCodec.Pod =
        SensiboCodec.singlePod(call(SensiboCodec.podUrl(podId, apiKey))) ?: throw WifiException(WifiError.PROTOCOL, "no pod")

    suspend fun setState(apiKey: String, podId: String, body: String) { call(SensiboCodec.acStatesUrl(podId, apiKey), "POST", body) }
}

class SensiboAcController(private val apiKey: String?, private val podId: String?) : WifiAcController {
    private var last: SensiboCodec.Pod? = null

    private fun creds() = (apiKey ?: throw WifiException(WifiError.AUTH, "api key")) to (podId ?: throw WifiException(WifiError.PROTOCOL, "no pod id"))

    override suspend fun read(): WifiResult<WifiAcStatus> = wifiCatching {
        val (k, id) = creds()
        val p = SensiboApi.pod(k, id)
        last = p
        val s = SensiboCodec.state(p)
        WifiAcStatus(s, SensiboCodec.caps(p, s.mode), p.roomC)
    }

    override suspend fun write(state: WifiAcState): WifiResult<Unit> = wifiCatching {
        val (k, id) = creds()
        SensiboApi.setState(k, id, SensiboCodec.acStateBody(state, last))
    }
}
