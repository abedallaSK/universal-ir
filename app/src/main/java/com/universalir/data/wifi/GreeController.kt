package com.universalir.data.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gree-module air conditioner over UDP 7000. The first call binds (generic key → per-device key, persisted via
 * [saveKey]); status and commands are then encrypted with the device key. Newer GCM firmware is tried as a fallback.
 */
class GreeController(device: SavedWifiDevice, private val saveKey: suspend (key: String, gcm: Boolean) -> Unit) {
    private val ip = device.ip
    private val mac = device.mac
    private var key = device.greeKey
    private var gcm = device.greeGcm
    private val mutex = Mutex()

    suspend fun status(): WifiResult<GreeState> = mutex.withLock {
        withKey { k -> GreePackets.parseStatus(exchange(GreePackets.statusRequest(mac!!, k, gcm), k)) ?: throw WifiException(WifiError.PROTOCOL, "bad status") }
    }

    /** Sends Gree columns (Pow, Mod, SetTem, WdSpd, SwUpDn…). */
    suspend fun set(values: Map<String, Int>): WifiResult<Unit> = mutex.withLock {
        withKey { k ->
            val res = exchange(GreePackets.commandRequest(mac!!, k, gcm, values), k)
            if (!GreePackets.commandAccepted(res)) throw WifiException(WifiError.PROTOCOL, "command rejected")
        }
    }

    /** Runs [block] with a bound key; on a decrypt failure the key is assumed stale and the unit is re-bound once. */
    private suspend fun <T> withKey(block: suspend (String) -> T): WifiResult<T> {
        if (mac.isNullOrBlank()) return WifiResult.Err(WifiError.PROTOCOL, "unknown MAC")
        val hadKey = key != null
        val k = key ?: when (val b = bind()) { is WifiResult.Ok -> b.value; is WifiResult.Err -> return b }
        val first = wifiCatching { block(k) }
        if (first is WifiResult.Err && first.error == WifiError.PROTOCOL && hadKey) {
            key = null
            val fresh = when (val b = bind()) { is WifiResult.Ok -> b.value; is WifiResult.Err -> return first }
            return wifiCatching { block(fresh) }
        }
        return first
    }

    private suspend fun bind(): WifiResult<String> {
        var last: WifiResult<String> = WifiResult.Err(WifiError.UNREACHABLE)
        for (useGcm in listOf(gcm, !gcm)) {
            val generic = if (useGcm) GreeCrypto.GENERIC_GCM_KEY else GreeCrypto.GENERIC_KEY
            last = wifiCatching {
                GreePackets.parseBindKey(exchange(GreePackets.bindRequest(mac!!, useGcm), generic)) ?: throw WifiException(WifiError.PROTOCOL, "bind refused")
            }
            if (last is WifiResult.Ok) {
                key = last.value; gcm = useGcm
                runCatching { saveKey(last.value, useGcm) }
                return last
            }
        }
        return last
    }

    /** One request/reply round trip (two attempts). Throws on timeout or undecryptable replies. */
    private suspend fun exchange(request: String, decryptKey: String): JsonObject = runInterruptible(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2500
            val target = InetAddress.getByName(ip)
            val bytes = request.toByteArray()
            var lastError: Exception = SocketTimeoutException("no reply from $ip")
            repeat(2) {
                socket.send(DatagramPacket(bytes, bytes.size, target, GreePackets.PORT))
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try { socket.receive(p) } catch (e: SocketTimeoutException) { lastError = e; break }
                    if (p.address != target) continue
                    val text = String(p.data, 0, p.length, Charsets.UTF_8)
                    return@runInterruptible GreePackets.openPack(text, decryptKey) ?: throw WifiException(WifiError.PROTOCOL, "undecryptable reply")
                }
            }
            throw lastError
        }
    }

    companion object {
        const val POW = "Pow"; const val MOD = "Mod"; const val SET_TEM = "SetTem"; const val WD_SPD = "WdSpd"; const val SW_UP_DN = "SwUpDn"
        val TEMP_RANGE = 16..30
    }
}

/** Creates controllers for saved devices; pairing secrets are written back to [WifiDeviceStore]. */
@Singleton
class WifiControllers @Inject constructor(private val store: WifiDeviceStore) {
    private val atvMutex = Mutex()
    @Volatile private var atvKeys: AtvKeys? = null

    fun tv(d: SavedWifiDevice): TvController? = when (d.protocol) {
        WifiProtocol.ROKU_ECP -> RokuController(d.ip, d.port.takeIf { it > 0 } ?: 8060)
        WifiProtocol.SAMSUNG_TIZEN -> SamsungController(d) { t -> store.update(d.id) { it.copy(token = t) } }
        WifiProtocol.LG_WEBOS -> LgWebOsController(d) { k -> store.update(d.id) { it.copy(clientKey = k) } }
        WifiProtocol.ANDROID_TV -> AndroidTvController(d) { androidTvKeys() }
        else -> null
    }

    fun gree(d: SavedWifiDevice): GreeController? =
        if (d.protocol == WifiProtocol.GREE) GreeController(d) { k, gcm -> store.update(d.id) { it.copy(greeKey = k, greeGcm = gcm) } } else null

    /** One AC controller for every Wi-Fi AC protocol. */
    fun ac(d: SavedWifiDevice): WifiAcController? = when (d.protocol) {
        WifiProtocol.GREE -> gree(d)?.let(::GreeAcController)
        WifiProtocol.DAIKIN -> DaikinController(d.ip, d.port.takeIf { it > 0 } ?: 80)
        WifiProtocol.ELECTRA -> ElectraAcController(d.cloudToken, d.cloudImei, d.remoteId)
        WifiProtocol.SENSIBO -> SensiboAcController(d.apiKey, d.remoteId)
        else -> null
    }

    fun androidTvPairing(d: SavedWifiDevice): AndroidTvPairing = AndroidTvPairing(d.ip, { androidTvKeys() }, "Universal IR Remote")

    suspend fun markAndroidTvPaired(id: String) = store.update(id) { it.copy(paired = true) }

    /**
     * This app's Android TV client identity: a software RSA-2048 key + self-signed certificate, created once and kept
     * encrypted (AES-GCM, KeyStore key) in [WifiDeviceStore]. If it can't be persisted it still works for this run.
     */
    suspend fun androidTvKeys(): AtvKeys = atvMutex.withLock {
        atvKeys?.let { return@withLock it }
        val loaded = store.atvIdentity()?.let(AtvKeys::fromData)
        val keys = loaded ?: withContext(Dispatchers.Default) { AtvKeys.generate() }.also { k ->
            runCatching { store.saveAtvIdentity(k.toData()) }
        }
        atvKeys = keys
        keys
    }
}
