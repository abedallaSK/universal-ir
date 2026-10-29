package com.universalir.ir.format

import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.PatternValidator
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.consumer.ConsumerProtocols
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The app's internal, importer-independent representation of a code set. Every importer converts
 * to this; the database stores it per code set; export writes it back as JSON.
 */
@Serializable
data class CanonicalCodeSet(
    val formatVersion: Int = FORMAT_VERSION,
    val id: String,
    /** "raw", a consumer protocol id (e.g. "NEC"), "smartir_table", or an AC protocol id. */
    val protocolId: String,
    val category: DeviceCategory,
    val brand: String,
    val models: List<String> = emptyList(),
    val remoteModel: String? = null,
    val commands: List<CanonicalCommand>,
    val source: CodeSource,
    val verification: String = "SOURCE_DISCOVERED",
    val notes: List<String> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 1
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    }
}

@Serializable
data class CodeSource(
    val repository: String,
    val url: String,
    val path: String? = null,
    val license: String,
    val attribution: String? = null,
)

@Serializable
data class CanonicalCommand(
    /** Normalised command name (see [CommandNames]) or an AC lookup key like "cool/low/24". */
    val name: String,
    val label: String = name,
    val kind: CommandKind,
    val carrierHz: Int? = null,
    val timings: List<Int>? = null,
    val frameStarts: List<Int>? = null,
    /** Extra times the whole pattern is resent (protocol-defined repeat). */
    val repeat: Int = 0,
    val protocol: String? = null,
    val address: Long? = null,
    val command: Long? = null,
)

@Serializable
enum class CommandKind {
    @SerialName("raw") RAW,
    @SerialName("parsed") PARSED,
}

/** Converts a canonical command to a transmittable pattern, with validation. */
object CanonicalRenderer {
    fun render(cmd: CanonicalCommand): EncodeResult {
        val pattern: IrPattern = when (cmd.kind) {
            CommandKind.RAW -> {
                val t = cmd.timings ?: return EncodeResult.Failure(listOf("Raw command '${cmd.name}' has no timings"))
                IrPattern(cmd.carrierHz ?: 38_000, t.toIntArray(), cmd.frameStarts ?: listOf(0))
            }
            CommandKind.PARSED -> {
                val proto = ConsumerProtocols.byId(cmd.protocol ?: "")
                    ?: return EncodeResult.Failure(listOf("Protocol '${cmd.protocol}' is not implemented"))
                return proto.encode(cmd.address ?: 0, cmd.command ?: 0)
            }
        }
        val issues = PatternValidator.validate(pattern)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues.map { it.message })
        return EncodeResult.Success(
            com.universalir.ir.core.IrTransmission.single(pattern, cmd.name),
            listOf("${pattern.size} timings @ ${pattern.carrierHz} Hz"),
            cmd.label,
        )
    }
}

/** Normalises the many spellings of common buttons found in public databases. */
object CommandNames {
    const val POWER = "power"
    const val POWER_ON = "power_on"
    const val POWER_OFF = "power_off"
    const val VOL_UP = "vol_up"
    const val VOL_DOWN = "vol_down"
    const val MUTE = "mute"
    const val CH_UP = "ch_up"
    const val CH_DOWN = "ch_down"
    const val UP = "up"
    const val DOWN = "down"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val OK = "ok"
    const val BACK = "back"
    const val HOME = "home"
    const val MENU = "menu"
    const val INPUT = "input"
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val STOP = "stop"
    const val NEXT = "next"
    const val PREV = "prev"
    const val FAST_FORWARD = "ff"
    const val REWIND = "rew"
    const val SPEED = "speed"
    const val OSCILLATE = "oscillate"
    const val TIMER = "timer"
    const val MODE = "mode"
    const val LIGHT = "light"
    const val KEYSTONE_UP = "keystone_up"
    const val KEYSTONE_DOWN = "keystone_down"
    const val FOCUS_IN = "focus_in"
    const val FOCUS_OUT = "focus_out"

    // Lights / LED strips. Colours are "color:<name>", effects "effect:<name>".
    const val BRIGHTNESS_UP = "brightness_up"
    const val BRIGHTNESS_DOWN = "brightness_down"
    const val SPEED_UP = "speed_up"
    const val SPEED_DOWN = "speed_down"
    const val COLOR_TEMP_COOL = "color_temp_cool"
    const val COLOR_TEMP_WARM = "color_temp_warm"
    const val NIGHT_LIGHT = "night_light"
    const val COLOR_PREFIX = "color:"
    const val EFFECT_PREFIX = "effect:"

    // Extra TV / TV-box buttons.
    const val INFO = "info"
    const val SETTINGS = "settings"
    const val EXIT = "exit"
    const val GUIDE = "guide"
    const val SUBTITLE = "subtitle"
    const val AUDIO = "audio"
    const val APPS = "apps"
    const val MOUSE = "mouse"
    const val YOUTUBE = "youtube"
    const val NETFLIX = "netflix"
    const val RECORD = "record"
    const val LAST = "last"
    const val LIST = "list"
    const val SLEEP = "sleep"
    const val ASPECT = "aspect"
    const val PAGE_UP = "page_up"
    const val PAGE_DOWN = "page_down"
    const val RED = "red"
    const val GREEN = "green"
    const val YELLOW = "yellow"
    const val BLUE = "blue"

    private val aliases: Map<String, String> = buildMap {
        listOf("power", "pwr", "power toggle", "on/off", "onoff", "standby", "power_toggle").forEach { put(it, POWER) }
        listOf("power on", "poweron", "on", "power_on", "discrete on").forEach { put(it, POWER_ON) }
        listOf("power off", "poweroff", "off", "power_off", "discrete off").forEach { put(it, POWER_OFF) }
        listOf("vol+", "vol_up", "volume +", "volume up", "volup", "vol up", "volume+", "volume_up").forEach { put(it, VOL_UP) }
        listOf("vol-", "vol_dn", "vol_down", "volume -", "volume down", "voldown", "vol down", "volume-", "volume_down").forEach { put(it, VOL_DOWN) }
        listOf("mute", "muting").forEach { put(it, MUTE) }
        listOf("ch+", "ch_next", "channel +", "channel up", "ch up", "chup", "channel+", "ch_up", "program +", "chan next", "chan+", "chan up").forEach { put(it, CH_UP) }
        listOf("ch-", "ch_prev", "channel -", "channel down", "ch down", "chdown", "channel-", "ch_down", "program -", "chan prev", "chan-", "chan down").forEach { put(it, CH_DOWN) }
        listOf("up", "cursor up", "arrow up", "nav up").forEach { put(it, UP) }
        listOf("down", "cursor down", "arrow down", "nav down").forEach { put(it, DOWN) }
        listOf("left", "cursor left", "arrow left", "nav left").forEach { put(it, LEFT) }
        listOf("right", "cursor right", "arrow right", "nav right").forEach { put(it, RIGHT) }
        listOf("ok", "enter", "select", "confirm").forEach { put(it, OK) }
        listOf("back", "return").forEach { put(it, BACK) }
        listOf("home", "smart hub", "smarthub").forEach { put(it, HOME) }
        put("menu", MENU)
        listOf("settings", "setup", "setting").forEach { put(it, SETTINGS) }
        put("exit", EXIT)
        listOf("input", "source", "sources", "input source", "av", "tv/av", "input select").forEach { put(it, INPUT) }
        listOf("play", "play/pause").forEach { put(it, PLAY) }
        put("pause", PAUSE); put("stop", STOP)
        listOf("next", "skip +", "skip_fwd", "next track").forEach { put(it, NEXT) }
        listOf("prev", "previous", "skip -", "skip_back", "previous track").forEach { put(it, PREV) }
        listOf("ff", "fast forward", "fast_fo", "forward").forEach { put(it, FAST_FORWARD) }
        listOf("rew", "rewind", "fast_ba").forEach { put(it, REWIND) }
        listOf("speed", "speed+", "fan speed", "speed_up").forEach { put(it, SPEED) }
        listOf("oscillate", "osc", "swing", "rotate").forEach { put(it, OSCILLATE) }
        put("timer", TIMER); put("mode", MODE); listOf("light", "led", "lamp").forEach { put(it, LIGHT) }
        listOf("keystone+", "keystone up", "keystone_up").forEach { put(it, KEYSTONE_UP) }
        listOf("keystone-", "keystone down", "keystone_down").forEach { put(it, KEYSTONE_DOWN) }
        listOf("focus+", "focus in", "focus_in").forEach { put(it, FOCUS_IN) }
        listOf("focus-", "focus out", "focus_out").forEach { put(it, FOCUS_OUT) }
        listOf("brightness up", "brightness+", "brightness +", "bright+", "bright up", "brighten", "brightness_up").forEach { put(it, BRIGHTNESS_UP) }
        listOf("brightness down", "brightness-", "brightness -", "bright-", "bright down", "dim", "dimmer", "brightness_down").forEach { put(it, BRIGHTNESS_DOWN) }
        listOf("info", "information", "display", "osd").forEach { put(it, INFO) }
        listOf("guide", "epg").forEach { put(it, GUIDE) }
        listOf("subtitle", "subtitles", "sub-title", "cc").forEach { put(it, SUBTITLE) }
        listOf("audio", "audio track", "lang", "language").forEach { put(it, AUDIO) }
        listOf("apps", "app", "applications", "all apps").forEach { put(it, APPS) }
        listOf("mouse", "mouse mode", "cursor").forEach { put(it, MOUSE) }
        listOf("youtube", "you tube").forEach { put(it, YOUTUBE) }
        put("netflix", NETFLIX)
        listOf("record", "rec").forEach { put(it, RECORD) }
        listOf("last", "recall", "pre-ch", "prev ch").forEach { put(it, LAST) }
        listOf("list", "ch list", "channel list").forEach { put(it, LIST) }
        put("sleep", SLEEP)
        listOf("aspect", "ratio", "aspect ratio").forEach { put(it, ASPECT) }
        listOf("page up", "page+", "page +", "pg+", "page_up").forEach { put(it, PAGE_UP) }
        listOf("page down", "page-", "page -", "pg-", "page_down").forEach { put(it, PAGE_DOWN) }
        listOf("red", "green", "yellow", "blue").forEach { put(it, it) }
        (0..9).forEach { put(it.toString(), "digit_$it"); put("digit $it", "digit_$it"); put("num_$it", "digit_$it") }
    }

    fun normalise(raw: String): String {
        val key = raw.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")
        return aliases[key] ?: aliases[raw.trim().lowercase()] ?: raw.trim().lowercase().replace(Regex("[^a-z0-9+\\-]+"), "_").trim('_')
    }
}
