package com.universalir.data.wifi

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Daikin Wi-Fi adapters with the local HTTP API (BRP069A/B, BRP072A, built-in "Daikin Online Controller" units):
 * `/common/basic_info`, `/aircon/get_control_info`, `/aircon/set_control_info`, `/aircon/get_sensor_info`.
 * Replies are `key=value,key=value` with `ret=OK`. Pure Kotlin (testable); see pydaikin for the field meanings.
 */
object DaikinCodec {
    const val UDP_PORT = 30050
    const val UDP_REPLY_PORT = 30000
    const val DISCOVERY = "DAIKIN_UDP/common/basic_info"
    val TEMP_RANGE = 18..30

    /** Parses `ret=OK,pow=1,name=%4c%69…` into a map; values are percent-decoded. */
    fun parse(body: String): Map<String, String> = body.trim().split(',').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i).trim() to decode(part.substring(i + 1).trim())
    }.toMap()

    private fun decode(v: String): String =
        if ('%' in v) runCatching { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") }.getOrDefault(v) else v

    fun isOk(m: Map<String, String>) = m["ret"] == "OK"

    fun mode(code: String?): WifiAcMode? = when (code) {
        "0", "1", "7" -> WifiAcMode.AUTO
        "2" -> WifiAcMode.DRY
        "3" -> WifiAcMode.COOL
        "4" -> WifiAcMode.HEAT
        "6" -> WifiAcMode.FAN
        else -> null
    }

    fun modeCode(m: WifiAcMode) = when (m) {
        WifiAcMode.AUTO -> "0"; WifiAcMode.DRY -> "2"; WifiAcMode.COOL -> "3"; WifiAcMode.HEAT -> "4"; WifiAcMode.FAN -> "6"
    }

    fun fan(rate: String?): WifiFan = when (rate) {
        "3", "4", "B" -> WifiFan.LOW
        "5" -> WifiFan.MEDIUM
        "6", "7" -> WifiFan.HIGH
        else -> WifiFan.AUTO
    }

    fun fanCode(f: WifiFan) = when (f) { WifiFan.AUTO -> "A"; WifiFan.LOW -> "3"; WifiFan.MEDIUM -> "5"; WifiFan.HIGH -> "7" }

    /** Reads get_control_info. Off units report pow=0 (and sometimes mode=10); the last mode is kept. */
    fun state(c: Map<String, String>): WifiAcState {
        val mode = mode(c["mode"]) ?: WifiAcMode.COOL
        val temp = c["stemp"]?.toDoubleOrNull()?.let { Math.round(it).toInt() }
            ?: c["dt${modeCode(mode)}"]?.toDoubleOrNull()?.let { Math.round(it).toInt() }
            ?: 24
        val swing = when {
            c.containsKey("f_dir_ud") -> c["f_dir_ud"] == "S" || c["f_dir_lr"] == "S"
            else -> (c["f_dir"] ?: "0") != "0"
        }
        return WifiAcState(power = c["pow"] == "1" && c["mode"] != "10", mode = mode, targetC = temp, fan = fan(c["f_rate"]), swing = swing)
    }

    fun caps(c: Map<String, String>) = WifiAcCaps(
        tempRange = TEMP_RANGE,
        fans = if (c.containsKey("f_rate")) WifiFan.entries else emptyList(),
        swing = c.containsKey("f_dir") || c.containsKey("f_dir_ud"),
        tempIgnoredIn = setOf(WifiAcMode.FAN, WifiAcMode.DRY),
    )

    /** Indoor temperature from get_sensor_info ("htemp=23.5"); "-" means not reported. */
    fun roomTemp(sensor: Map<String, String>): Double? = sensor["htemp"]?.toDoubleOrNull()

    /**
     * Query string for set_control_info. The adapter wants pow, mode, stemp, shum (and f_rate/f_dir if supported) every
     * time; for dry/fan the per-mode memory (dtN/dhN) from [current] is reused because those modes have no set point.
     */
    fun controlQuery(s: WifiAcState, current: Map<String, String>): String {
        val code = modeCode(s.mode)
        val stemp = when (s.mode) {
            WifiAcMode.COOL, WifiAcMode.HEAT, WifiAcMode.AUTO -> s.targetC.coerceIn(TEMP_RANGE).toString()
            else -> current["dt$code"]?.takeIf { it.isNotBlank() } ?: if (s.mode == WifiAcMode.DRY) "M" else "--"
        }
        val shum = current["dh$code"]?.takeIf { it.isNotBlank() } ?: when (s.mode) { WifiAcMode.DRY -> "AUTO"; WifiAcMode.FAN -> "--"; else -> "0" }
        val params = linkedMapOf("pow" to if (s.power) "1" else "0", "mode" to code, "stemp" to stemp, "shum" to shum)
        if (current.containsKey("f_rate")) params["f_rate"] = fanCode(s.fan)
        if (current.containsKey("f_dir_ud")) {
            params["f_dir_ud"] = if (s.swing) "S" else "0"
            params["f_dir_lr"] = current["f_dir_lr"] ?: "0"
        } else if (current.containsKey("f_dir")) {
            val cur = current["f_dir"] ?: "0"
            params["f_dir"] = if (!s.swing) "0" else if (cur != "0") cur else "1"
        }
        return params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
    }

    data class Basic(val mac: String?, val name: String?, val type: String?, val version: String?)

    fun basic(m: Map<String, String>) = Basic(m["mac"], m["name"]?.takeIf { it.isNotBlank() }, m["type"], m["ver"])
}

class DaikinController(private val ip: String, private val port: Int = 80) : WifiAcController {
    private var lastControl: Map<String, String> = emptyMap()

    private suspend fun get(path: String): Map<String, String> {
        val (code, body) = LanHttp.request("http://$ip${if (port == 80) "" else ":$port"}$path", timeoutMs = 4000)
        // Newer BRP072C / Onecta-only adapters answer 403/404 on plain HTTP: they need HTTPS key registration.
        if (code == 403 || code == 404) throw WifiException(WifiError.UNSUPPORTED, "HTTP $code")
        if (code !in 200..299) throw WifiException(WifiError.PROTOCOL, "HTTP $code")
        val m = DaikinCodec.parse(body)
        if (!DaikinCodec.isOk(m)) throw WifiException(WifiError.PROTOCOL, m["ret"] ?: "no ret")
        return m
    }

    override suspend fun read(): WifiResult<WifiAcStatus> = wifiCatching {
        val control = get("/aircon/get_control_info")
        lastControl = control
        val room = runCatching { DaikinCodec.roomTemp(get("/aircon/get_sensor_info")) }.getOrNull()
        WifiAcStatus(DaikinCodec.state(control), DaikinCodec.caps(control), room)
    }

    override suspend fun write(state: WifiAcState): WifiResult<Unit> = wifiCatching {
        if (lastControl.isEmpty()) lastControl = get("/aircon/get_control_info")
        get("/aircon/set_control_info?" + DaikinCodec.controlQuery(state, lastControl))
        Unit
    }

    companion object {
        /** Reads basic_info to confirm an IP is a Daikin adapter (manual "Add by IP"). */
        suspend fun probe(ip: String): WifiResult<DaikinCodec.Basic> = wifiCatching {
            val (code, body) = LanHttp.request("http://$ip/common/basic_info", timeoutMs = 3000)
            val m = DaikinCodec.parse(body)
            if (code !in 200..299 || !DaikinCodec.isOk(m)) throw WifiException(WifiError.PROTOCOL, "not a Daikin adapter")
            DaikinCodec.basic(m)
        }
    }
}
