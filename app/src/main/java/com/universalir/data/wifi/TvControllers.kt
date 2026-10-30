package com.universalir.data.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A network TV remote. Implementations never throw to callers; everything comes back as [WifiResult]. */
interface TvController {
    val supported: Set<TvKey>
    /** Apps this controller can open directly. */
    val apps: Set<TvApp> get() = emptySet()
    /** Live state the TV reports (power, volume, current app), or null when the protocol doesn't report any. */
    val status: StateFlow<TvStatus>? get() = null
    /** Connects, pairing on first use; [onPairing] fires when the TV is showing its "allow" prompt. */
    suspend fun connect(onPairing: () -> Unit = {}): WifiResult<Unit>
    suspend fun press(key: TvKey): WifiResult<Unit>
    suspend fun launch(app: TvApp): WifiResult<Unit> = WifiResult.Err(WifiError.UNSUPPORTED)
    fun close()
}

private val lanJson = Json { ignoreUnknownKeys = true }
private fun parseObj(text: String): JsonObject? = runCatching { lanJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content
private fun JsonObject.obj(k: String) = this[k] as? JsonObject

// ------------------------------------------------------------------ Roku ECP

/** Roku External Control Protocol: stateless HTTP POSTs to port 8060. */
class RokuController(private val ip: String, private val port: Int = 8060) : TvController {
    private val keys = mapOf(
        TvKey.POWER to "Power", TvKey.UP to "Up", TvKey.DOWN to "Down", TvKey.LEFT to "Left", TvKey.RIGHT to "Right",
        TvKey.OK to "Select", TvKey.BACK to "Back", TvKey.HOME to "Home", TvKey.MENU to "Info",
        TvKey.VOL_UP to "VolumeUp", TvKey.VOL_DOWN to "VolumeDown", TvKey.MUTE to "VolumeMute",
        TvKey.CH_UP to "ChannelUp", TvKey.CH_DOWN to "ChannelDown",
        TvKey.PLAY to "Play", TvKey.REWIND to "Rev", TvKey.FAST_FORWARD to "Fwd", TvKey.INPUT to "InputHDMI1",
    )
    override val supported: Set<TvKey> = keys.keys

    override suspend fun connect(onPairing: () -> Unit): WifiResult<Unit> =
        status(wifiCatching { LanHttp.request("http://$ip:$port/query/device-info").first })

    override suspend fun press(key: TvKey): WifiResult<Unit> {
        val name = keys[key] ?: return WifiResult.Err(WifiError.UNSUPPORTED)
        var r = post(name)
        // Streaming sticks have no Power key; Roku TVs accept both.
        if (key == TvKey.POWER && r is WifiResult.Err && r.error == WifiError.PROTOCOL) r = post("PowerOff")
        return r
    }

    private suspend fun post(name: String): WifiResult<Unit> =
        status(wifiCatching { LanHttp.request("http://$ip:$port/keypress/$name", method = "POST").first })

    /** 403 means "Control by mobile apps" is disabled in the Roku's network settings. */
    private fun status(r: WifiResult<Int>): WifiResult<Unit> = when (r) {
        is WifiResult.Err -> r
        is WifiResult.Ok -> when (r.value) {
            in 200..299 -> WifiResult.Ok(Unit)
            403 -> WifiResult.Err(WifiError.DENIED, "HTTP 403")
            else -> WifiResult.Err(WifiError.PROTOCOL, "HTTP ${r.value}")
        }
    }

    override fun close() = Unit
}

// ------------------------------------------------------------------ Samsung Tizen

/**
 * Samsung 2016+ remote-control channel. The first connection makes the TV show an allow prompt; the returned token is
 * persisted through [saveToken] so later connections are silent. Falls back to the legacy, token-less ws://8001.
 */
class SamsungController(private val device: SavedWifiDevice, private val saveToken: suspend (String) -> Unit) : TvController {
    private val mutex = Mutex()
    private var session: WsSession? = null
    private var token = device.token
    private val appName = Base64.getEncoder().encodeToString("Universal IR Remote".toByteArray())

    private val keys = mapOf(
        TvKey.POWER to "KEY_POWER", TvKey.UP to "KEY_UP", TvKey.DOWN to "KEY_DOWN", TvKey.LEFT to "KEY_LEFT", TvKey.RIGHT to "KEY_RIGHT",
        TvKey.OK to "KEY_ENTER", TvKey.BACK to "KEY_RETURN", TvKey.HOME to "KEY_HOME", TvKey.MENU to "KEY_MENU", TvKey.INPUT to "KEY_SOURCE",
        TvKey.VOL_UP to "KEY_VOLUP", TvKey.VOL_DOWN to "KEY_VOLDOWN", TvKey.MUTE to "KEY_MUTE", TvKey.CH_UP to "KEY_CHUP", TvKey.CH_DOWN to "KEY_CHDOWN",
        TvKey.PLAY to "KEY_PLAY", TvKey.PAUSE to "KEY_PAUSE", TvKey.REWIND to "KEY_REWIND", TvKey.FAST_FORWARD to "KEY_FF",
    )
    override val supported: Set<TvKey> = keys.keys

    override suspend fun connect(onPairing: () -> Unit): WifiResult<Unit> = mutex.withLock { openLocked(onPairing) }

    private suspend fun openLocked(onPairing: () -> Unit): WifiResult<Unit> {
        if (session?.isOpen == true) return WifiResult.Ok(Unit)
        val path = "/api/v2/channels/samsung.remote.control?name=$appName"
        val urls = listOf("wss://${device.ip}:8002$path" + (token?.let { "&token=$it" } ?: ""), "ws://${device.ip}:8001$path")
        var last: WifiResult<Unit> = WifiResult.Err(WifiError.UNREACHABLE)
        for (url in urls) {
            val s = try { WsSession.connect(LanHttp.tvClient, url, 9000) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                last = WifiResult.Err(errorOf(e), e.message); continue
            }
            val r = awaitAuthorization(s, onPairing)
            if (r is WifiResult.Ok) { session = s; return r }
            s.close(); last = r
            // A real answer from the TV (denied / prompt ignored) means the fallback port won't help.
            if ((r as WifiResult.Err).error == WifiError.DENIED || r.error == WifiError.TIMEOUT) return r
        }
        return last
    }

    private suspend fun awaitAuthorization(s: WsSession, onPairing: () -> Unit): WifiResult<Unit> {
        val deadline = System.currentTimeMillis() + 45_000
        var prompted = false
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return WifiResult.Err(WifiError.TIMEOUT, "pairing prompt not answered")
            val msg = s.receive(if (prompted) left else minOf(left, 1500))
            if (msg == null) {
                if (!s.isOpen) return WifiResult.Err(WifiError.UNREACHABLE, "closed by TV")
                if (!prompted) { prompted = true; onPairing() }
                continue
            }
            val o = parseObj(msg) ?: continue
            when (o.str("event")) {
                "ms.channel.connect" -> {
                    o.obj("data")?.str("token")?.takeIf { it.isNotBlank() && it != token }?.let { token = it; runCatching { saveToken(it) } }
                    return WifiResult.Ok(Unit)
                }
                "ms.channel.unauthorized" -> return WifiResult.Err(WifiError.DENIED, "unauthorized")
                "ms.channel.timeOut" -> return WifiResult.Err(WifiError.TIMEOUT, "pairing timed out on TV")
            }
        }
    }

    override suspend fun press(key: TvKey): WifiResult<Unit> = mutex.withLock {
        val code = keys[key] ?: return@withLock WifiResult.Err(WifiError.UNSUPPORTED)
        val payload = buildJsonObject {
            put("method", "ms.remote.control")
            put("params", buildJsonObject { put("Cmd", "Click"); put("DataOfCmd", code); put("Option", "false"); put("TypeOfRemote", "SendRemoteKey") })
        }.toString()
        repeat(2) {
            val open = openLocked {}
            if (open is WifiResult.Err) return@withLock open
            if (session?.send(payload) == true) return@withLock WifiResult.Ok(Unit)
            session?.close(); session = null
        }
        WifiResult.Err(WifiError.UNREACHABLE, "socket closed")
    }

    override fun close() { session?.close(); session = null }
}

// ------------------------------------------------------------------ LG webOS

/**
 * LG webOS SSAP over ws://3000 (wss://3001 fallback). Registers with PROMPT pairing and stores the client-key via
 * [saveKey]; navigation keys go through the pointer-input socket, the rest are ssap:// requests.
 */
class LgWebOsController(private val device: SavedWifiDevice, private val saveKey: suspend (String) -> Unit) : TvController {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: WsSession? = null
    private var pointer: WsSession? = null
    private var reader: Job? = null
    private var clientKey = device.clientKey
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val ids = AtomicInteger(1)

    private val ssap = mapOf(
        TvKey.POWER to "ssap://system/turnOff", TvKey.VOL_UP to "ssap://audio/volumeUp", TvKey.VOL_DOWN to "ssap://audio/volumeDown",
        TvKey.CH_UP to "ssap://tv/channelUp", TvKey.CH_DOWN to "ssap://tv/channelDown",
        TvKey.PLAY to "ssap://media.controls/play", TvKey.PAUSE to "ssap://media.controls/pause",
        TvKey.REWIND to "ssap://media.controls/rewind", TvKey.FAST_FORWARD to "ssap://media.controls/fastForward",
    )
    private val buttons = mapOf(
        TvKey.UP to "UP", TvKey.DOWN to "DOWN", TvKey.LEFT to "LEFT", TvKey.RIGHT to "RIGHT", TvKey.OK to "ENTER",
        TvKey.BACK to "BACK", TvKey.HOME to "HOME", TvKey.MENU to "MENU",
    )
    override val supported: Set<TvKey> = ssap.keys + buttons.keys + TvKey.MUTE

    override suspend fun connect(onPairing: () -> Unit): WifiResult<Unit> = mutex.withLock { openLocked(onPairing) }

    private suspend fun openLocked(onPairing: () -> Unit): WifiResult<Unit> {
        if (session?.isOpen == true) return WifiResult.Ok(Unit)
        var last: WifiResult<Unit> = WifiResult.Err(WifiError.UNREACHABLE)
        for (url in listOf("ws://${device.ip}:3000", "wss://${device.ip}:3001")) {
            val s = try { WsSession.connect(LanHttp.tvClient, url, 9000) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                last = WifiResult.Err(errorOf(e), e.message); continue
            }
            val r = register(s, onPairing)
            if (r is WifiResult.Ok) {
                session = s
                reader?.cancel()
                reader = scope.launch { for (m in s.incoming) parseObj(m)?.let { o -> o.str("id")?.let { pending.remove(it)?.complete(o) } } }
                return r
            }
            s.close(); last = r
            if ((r as WifiResult.Err).error == WifiError.DENIED || r.error == WifiError.TIMEOUT) return r
        }
        return last
    }

    private suspend fun register(s: WsSession, onPairing: () -> Unit): WifiResult<Unit> {
        val msg = buildJsonObject {
            put("type", "register"); put("id", "register_0")
            put("payload", buildJsonObject {
                put("forcePairing", false); put("pairingType", "PROMPT")
                clientKey?.let { put("client-key", it) }
                put("manifest", buildJsonObject {
                    put("manifestVersion", 1); put("appVersion", "1.1")
                    put("permissions", buildJsonArray { PERMISSIONS.forEach { add(JsonPrimitive(it)) } })
                })
            })
        }
        if (!s.send(msg.toString())) return WifiResult.Err(WifiError.UNREACHABLE, "closed")
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return WifiResult.Err(WifiError.TIMEOUT, "pairing prompt not answered")
            val text = s.receive(left) ?: return if (s.isOpen) WifiResult.Err(WifiError.TIMEOUT) else WifiResult.Err(WifiError.UNREACHABLE, "closed by TV")
            val o = parseObj(text) ?: continue
            when (o.str("type")) {
                "response" -> if (o.obj("payload")?.str("pairingType") == "PROMPT") onPairing()
                "registered" -> {
                    o.obj("payload")?.str("client-key")?.takeIf { it.isNotBlank() && it != clientKey }?.let { clientKey = it; runCatching { saveKey(it) } }
                    return WifiResult.Ok(Unit)
                }
                "error" -> return WifiResult.Err(WifiError.DENIED, o.str("error"))
            }
        }
    }

    private suspend fun request(uri: String, payload: JsonObject? = null, timeoutMs: Long = 4000): WifiResult<JsonObject> {
        val s = session ?: return WifiResult.Err(WifiError.UNREACHABLE)
        val id = "req_${ids.getAndIncrement()}"
        val d = CompletableDeferred<JsonObject>().also { pending[id] = it }
        val msg = buildJsonObject { put("type", "request"); put("id", id); put("uri", uri); payload?.let { put("payload", it) } }
        if (!s.send(msg.toString())) { pending.remove(id); return WifiResult.Err(WifiError.UNREACHABLE, "closed") }
        val resp = withTimeoutOrNull(timeoutMs) { d.await() }
        pending.remove(id)
        resp ?: return WifiResult.Err(WifiError.TIMEOUT)
        if (resp.str("type") == "error") return WifiResult.Err(WifiError.PROTOCOL, resp.str("error"))
        return WifiResult.Ok(resp.obj("payload") ?: JsonObject(emptyMap()))
    }

    private suspend fun button(name: String): WifiResult<Unit> {
        if (pointer?.isOpen != true) {
            val r = request("ssap://com.webos.service.networkinput/getPointerInputSocket")
            if (r is WifiResult.Err) return r
            val path = (r as WifiResult.Ok).value.str("socketPath") ?: return WifiResult.Err(WifiError.PROTOCOL, "no socketPath")
            pointer = try { WsSession.connect(LanHttp.tvClient, path, 4000) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                return WifiResult.Err(errorOf(e), e.message)
            }
        }
        return if (pointer?.send("type:button\nname:$name\n\n") == true) WifiResult.Ok(Unit) else WifiResult.Err(WifiError.UNREACHABLE, "pointer closed")
    }

    private suspend fun toggleMute(): WifiResult<Unit> {
        val muted = (request("ssap://audio/getStatus") as? WifiResult.Ok)?.value?.get("mute")?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: return button("MUTE")
        val r = request("ssap://audio/setMute", buildJsonObject { put("mute", !muted) })
        return if (r is WifiResult.Err) r else WifiResult.Ok(Unit)
    }

    override suspend fun press(key: TvKey): WifiResult<Unit> = mutex.withLock {
        repeat(2) { attempt ->
            val open = openLocked {}
            if (open is WifiResult.Err) return@withLock open
            val r: WifiResult<Unit> = when (key) {
                TvKey.MUTE -> toggleMute()
                in buttons -> button(buttons.getValue(key))
                in ssap -> request(ssap.getValue(key)).let { if (it is WifiResult.Err) it else WifiResult.Ok(Unit) }
                else -> return@withLock WifiResult.Err(WifiError.UNSUPPORTED)
            }
            if (r is WifiResult.Ok || (r as WifiResult.Err).error != WifiError.UNREACHABLE || attempt == 1) return@withLock r
            dropSessions()
        }
        WifiResult.Err(WifiError.UNREACHABLE)
    }

    private fun dropSessions() {
        reader?.cancel(); reader = null
        session?.close(); session = null
        pointer?.close(); pointer = null
        pending.values.forEach { it.cancel() }; pending.clear()
    }

    override fun close() { dropSessions(); scope.cancel() }

    private companion object {
        val PERMISSIONS = listOf(
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED", "CONTROL_AUDIO", "CONTROL_DISPLAY",
            "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
            "CONTROL_POWER", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_INPUT_TEXT", "READ_APP_STATUS", "READ_CURRENT_CHANNEL",
            "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "READ_POWER_STATE",
            "READ_COUNTRY_INFO", "READ_INSTALLED_APPS", "WRITE_NOTIFICATION_TOAST",
        )
    }
}
