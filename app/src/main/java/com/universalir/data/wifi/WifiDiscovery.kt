package com.universalir.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Device types offered by "Add by IP". */
enum class ManualKind { ANDROID_TV, SAMSUNG, LG, ROKU, GREE, DAIKIN }

/**
 * LAN discovery: SSDP (Samsung/LG/Roku/DIAL), mDNS via NsdManager (Cast, Android TV, AirPlay, HomeKit) and the Gree
 * UDP scan (Gree-module ACs, incl. Tadiran) and the Daikin UDP probe (port 30050). Each scan emits the growing, IP-deduplicated result list and completes after [scan]'s duration.
 */
@Singleton
class WifiDiscovery @Inject constructor(@ApplicationContext private val context: Context) {

    private val mdnsTypes = listOf("_googlecast._tcp", "_androidtvremote2._tcp", "_airplay._tcp", "_hap._tcp")

    /** True when the phone has a Wi-Fi (or Ethernet) network, so multicast/broadcast can reach the LAN. */
    @Suppress("DEPRECATION")
    fun onLan(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
        }
    }

    fun scan(durationMs: Long = 6000): Flow<List<DiscoveredDevice>> = channelFlow {
        val found = ConcurrentHashMap<String, DiscoveredDevice>()
        val lock = Mutex()
        val add: suspend (DiscoveredDevice) -> Unit = { d ->
            lock.withLock {
                found[d.ip] = found[d.ip]?.mergeWith(d) ?: d
                send(found.values.sortedWith(compareByDescending<DiscoveredDevice> { it.protocol.controllable }.thenBy { it.kind.ordinal }.thenBy { it.name.lowercase() }))
            }
        }
        send(emptyList())
        val multicast = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("universalir-discovery")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        try {
            withTimeoutOrNull(durationMs) {
                coroutineScope {
                    launch(Dispatchers.IO) { ssdp(add) }
                    launch(Dispatchers.IO) { gree(add) }
                    launch(Dispatchers.IO) { daikin(add) }
                    launch { mdns(add) }
                }
            }
        } finally {
            runCatching { if (multicast?.isHeld == true) multicast.release() }
        }
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- SSDP

    private suspend fun ssdp(add: suspend (DiscoveredDevice) -> Unit) = coroutineScope {
        val socket = runCatching { DatagramSocket(null).apply { reuseAddress = true; soTimeout = 400; bind(InetSocketAddress(0)) } }.getOrNull() ?: return@coroutineScope
        val seen = ConcurrentHashMap.newKeySet<String>()
        try {
            val group = InetAddress.getByName(Ssdp.ADDRESS)
            launch(Dispatchers.IO) {
                repeat(2) {
                    Ssdp.SEARCH_TARGETS.forEach { st ->
                        val bytes = Ssdp.mSearch(st).toByteArray()
                        runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, Ssdp.PORT)) }
                    }
                    delay(1200)
                }
            }
            val buf = ByteArray(4096)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                val ip = packet.address?.hostAddress ?: continue
                val r = Ssdp.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                val key = r.location ?: "$ip|${r.usn}"
                if (!seen.add(key)) continue
                launch(Dispatchers.IO) {
                    val desc = r.location?.let { loc ->
                        withTimeoutOrNull(2500) {
                            runCatching { LanHttp.request(loc, timeoutMs = 2000).second }.getOrNull()?.let(Ssdp::parseDescription)
                        }
                    }
                    val (kind, protocol) = Ssdp.classify(r, desc)
                    val port = when (protocol) {
                        WifiProtocol.ROKU_ECP -> 8060
                        WifiProtocol.SAMSUNG_TIZEN -> 8002
                        WifiProtocol.LG_WEBOS -> 3000
                        WifiProtocol.ANDROID_TV -> AtvRemoteProtocol.PORT
                        else -> Ssdp.portOf(r.location).coerceAtLeast(0)
                    }
                    val stable = r.uuid ?: ip
                    add(DiscoveredDevice(
                        id = "${protocol.name.lowercase()}-${LanMath.safeId(stable)}",
                        ip = ip,
                        name = desc?.friendlyName ?: defaultName(kind, desc?.manufacturer) ?: ip,
                        kind = kind, protocol = protocol, port = port,
                        manufacturer = desc?.manufacturer, model = desc?.modelName,
                        sources = setOf(DiscoverySource.SSDP),
                    ))
                }
            }
        } finally { socket.close() }
    }

    // ---------------------------------------------------------------- Gree

    private suspend fun gree(add: suspend (DiscoveredDevice) -> Unit) = coroutineScope {
        val socket = runCatching { DatagramSocket().apply { broadcast = true; soTimeout = 400 } }.getOrNull() ?: return@coroutineScope
        try {
            val targets = (listOf(InetAddress.getByName("255.255.255.255")) + subnetBroadcasts()).distinct()
            launch(Dispatchers.IO) {
                repeat(3) {
                    val bytes = GreePackets.SCAN.toByteArray()
                    targets.forEach { t -> runCatching { socket.send(DatagramPacket(bytes, bytes.size, t, GreePackets.PORT)) } }
                    delay(1500)
                }
            }
            val buf = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                val ip = packet.address?.hostAddress ?: continue
                val info = GreePackets.parseScan(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                add(greeDevice(ip, info))
            }
        } finally { socket.close() }
    }

    private fun greeDevice(ip: String, info: GreePackets.ScanInfo): DiscoveredDevice {
        val brand = info.brand?.replaceFirstChar { it.uppercase() }
        // Gree units often report a hex id as "name"; only use it when it looks human.
        val human = info.name?.takeIf { n -> !n.matches(Regex("[0-9a-fA-F]{6,}")) && !n.equals(info.mac, true) }
        return DiscoveredDevice(
            id = "gree-${LanMath.safeId(info.mac)}",
            ip = ip, name = human ?: "${brand ?: "Gree"} AC",
            kind = WifiDeviceKind.AC_GREE, protocol = WifiProtocol.GREE, port = GreePackets.PORT,
            manufacturer = brand, model = info.model?.takeIf { !it.equals(info.brand, true) } ?: info.version,
            mac = info.mac, sources = setOf(DiscoverySource.GREE),
        )
    }

    // ---------------------------------------------------------------- Daikin

    private suspend fun daikin(add: suspend (DiscoveredDevice) -> Unit) = coroutineScope {
        // The reference client listens on 30000; fall back to any port if another app holds it.
        val socket = runCatching {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 400; bind(InetSocketAddress(DaikinCodec.UDP_REPLY_PORT)) }
        }.getOrNull() ?: runCatching { DatagramSocket().apply { broadcast = true; soTimeout = 400 } }.getOrNull() ?: return@coroutineScope
        try {
            val targets = (listOf(InetAddress.getByName("255.255.255.255")) + subnetBroadcasts()).distinct()
            launch(Dispatchers.IO) {
                repeat(3) {
                    val bytes = DaikinCodec.DISCOVERY.toByteArray()
                    targets.forEach { t -> runCatching { socket.send(DatagramPacket(bytes, bytes.size, t, DaikinCodec.UDP_PORT)) } }
                    delay(1500)
                }
            }
            val buf = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
                val ip = packet.address?.hostAddress ?: continue
                val info = DaikinCodec.basic(DaikinCodec.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)))
                if (info.mac.isNullOrBlank()) continue
                add(daikinDevice(ip, info))
            }
        } finally { socket.close() }
    }

    private fun daikinDevice(ip: String, info: DaikinCodec.Basic) = DiscoveredDevice(
        id = "daikin-${LanMath.safeId(info.mac ?: ip)}", ip = ip, name = info.name ?: "Daikin AC",
        kind = WifiDeviceKind.AC, protocol = WifiProtocol.DAIKIN, port = 80,
        manufacturer = "Daikin", model = info.version, mac = info.mac, sources = setOf(DiscoverySource.DAIKIN),
    )

    // ---------------------------------------------------------------- Manual add

    /**
     * Builds a device for "Add by IP". Gree needs its MAC for every command, so the unit is asked directly (unicast
     * scan); Daikin and Roku are checked over HTTP. Samsung, LG and Android TV are added as-is and verified on connect.
     */
    suspend fun probe(kind: ManualKind, ip: String): WifiResult<DiscoveredDevice> {
        val addr = ip.trim()
        if (!LanMath.isPrivateIpv4(addr)) return WifiResult.Err(WifiError.UNSUPPORTED, "not a local IPv4 address")
        val safe = LanMath.safeId(addr)
        return when (kind) {
            ManualKind.ANDROID_TV -> WifiResult.Ok(DiscoveredDevice("androidtv-ip-$safe", addr, "Android TV", WifiDeviceKind.TV_ANDROID, WifiProtocol.ANDROID_TV, AtvRemoteProtocol.PORT))
            ManualKind.SAMSUNG -> WifiResult.Ok(DiscoveredDevice("samsung_tizen-ip-$safe", addr, "Samsung TV", WifiDeviceKind.TV_SAMSUNG, WifiProtocol.SAMSUNG_TIZEN, 8002))
            ManualKind.LG -> WifiResult.Ok(DiscoveredDevice("lg_webos-ip-$safe", addr, "LG TV", WifiDeviceKind.TV_LG, WifiProtocol.LG_WEBOS, 3000))
            ManualKind.ROKU -> wifiCatching {
                val (code, body) = LanHttp.request("http://$addr:8060/query/device-info", timeoutMs = 3000)
                if (code !in 200..299) throw WifiException(WifiError.PROTOCOL, "HTTP $code")
                val name = Regex("<user-device-name>([^<]+)</user-device-name>").find(body)?.groupValues?.get(1)
                    ?: Regex("<friendly-device-name>([^<]+)</friendly-device-name>").find(body)?.groupValues?.get(1)
                DiscoveredDevice("roku_ecp-ip-$safe", addr, name ?: "Roku", WifiDeviceKind.TV_ROKU, WifiProtocol.ROKU_ECP, 8060, manufacturer = "Roku")
            }
            ManualKind.DAIKIN -> when (val r = DaikinController.probe(addr)) {
                is WifiResult.Err -> r
                is WifiResult.Ok -> WifiResult.Ok(daikinDevice(addr, r.value))
            }
            ManualKind.GREE -> wifiCatching { greeUnicast(addr) }
        }
    }

    private suspend fun greeUnicast(addr: String): DiscoveredDevice = runInterruptible(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2000
            val target = InetAddress.getByName(addr)
            val bytes = GreePackets.SCAN.toByteArray()
            val buf = ByteArray(2048)
            var found: DiscoveredDevice? = null
            var attempt = 0
            while (found == null && attempt < 2) {
                attempt++
                socket.send(DatagramPacket(bytes, bytes.size, target, GreePackets.PORT))
                val p = DatagramPacket(buf, buf.size)
                val got = try { socket.receive(p); true } catch (_: SocketTimeoutException) { false }
                if (got) GreePackets.parseScan(String(p.data, 0, p.length, Charsets.UTF_8))?.let { found = greeDevice(addr, it) }
            }
            found ?: throw SocketTimeoutException("no reply from $addr")
        }
    }

    @Suppress("DEPRECATION")
    private fun subnetBroadcasts(): List<InetAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return cm.allNetworks.filter { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { n -> cm.getLinkProperties(n)?.linkAddresses.orEmpty() }
            .filter { it.address is Inet4Address }
            .mapNotNull { la -> runCatching { InetAddress.getByAddress(LanMath.broadcast(la.address.address, la.prefixLength)) }.getOrNull() }
    }

    // ---------------------------------------------------------------- mDNS

    private suspend fun mdns(add: suspend (DiscoveredDevice) -> Unit) = coroutineScope {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return@coroutineScope
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listeners = mdnsTypes.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { queue.trySend(info) }
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }.also { l -> runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) } }
        }
        val resolveLock = Mutex()
        val seen = HashSet<String>()
        try {
            for (info in queue) {
                if (!seen.add("${info.serviceType}|${info.serviceName}")) continue
                // Resolves run one at a time: pre-34 NsdManager fails concurrent resolves with FAILURE_ALREADY_ACTIVE.
                launch {
                    val resolved = resolveLock.withLock { resolve(nsd, info) } ?: return@launch
                    toDevice(resolved)?.let { add(it) }
                }
            }
        } finally {
            listeners.forEach { l -> runCatching { nsd.stopServiceDiscovery(l) } }
            queue.close()
        }
    }

    private class Resolved(val info: NsdServiceInfo, val host: InetAddress)

    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): Resolved? {
        repeat(3) { attempt ->
            val r = withTimeoutOrNull(3000) {
                if (Build.VERSION.SDK_INT >= 34) resolve34(nsd, info) else resolveLegacy(nsd, info)
            }
            when (r) {
                is ResolveOutcome.Ok -> return r.value
                is ResolveOutcome.Busy -> delay(250L * (attempt + 1))
                else -> return null
            }
        }
        return null
    }

    private sealed interface ResolveOutcome {
        class Ok(val value: Resolved) : ResolveOutcome
        data object Busy : ResolveOutcome
        data object Failed : ResolveOutcome
    }

    /** NSD callbacks may race (e.g. updated + lost); only the first one wins. */
    private fun <T> CancellableContinuation<T>.resumeOnce(v: T) { runCatching { if (isActive) resume(v) } }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(nsd: NsdManager, info: NsdServiceInfo): ResolveOutcome = suspendCancellableCoroutine { cont ->
        val ok = runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    cont.resumeOnce(if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) ResolveOutcome.Busy else ResolveOutcome.Failed)
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host
                    cont.resumeOnce(if (host is Inet4Address) ResolveOutcome.Ok(Resolved(si, host)) else ResolveOutcome.Failed)
                }
            })
        }.isSuccess
        if (!ok) cont.resumeOnce(ResolveOutcome.Failed)
    }

    @RequiresApi(34)
    private suspend fun resolve34(nsd: NsdManager, info: NsdServiceInfo): ResolveOutcome = suspendCancellableCoroutine { cont ->
        val direct = Executor { it.run() }
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                cont.resumeOnce(if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) ResolveOutcome.Busy else ResolveOutcome.Failed)
            }
            override fun onServiceUpdated(si: NsdServiceInfo) {
                val host = si.hostAddresses.firstOrNull { it is Inet4Address } ?: return
                cont.resumeOnce(ResolveOutcome.Ok(Resolved(si, host)))
                runCatching { nsd.unregisterServiceInfoCallback(this) }
            }
            override fun onServiceLost() { cont.resumeOnce(ResolveOutcome.Failed); runCatching { nsd.unregisterServiceInfoCallback(this) } }
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        if (runCatching { nsd.registerServiceInfoCallback(info, direct, callback) }.isFailure) cont.resumeOnce(ResolveOutcome.Failed)
        cont.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
    }

    private fun toDevice(r: Resolved): DiscoveredDevice? {
        val ip = r.host.hostAddress ?: return null
        val type = r.info.serviceType.lowercase()
        val txt = runCatching { r.info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) } }.getOrDefault(emptyMap())
        val atv = "androidtvremote" in type
        val kind = when {
            atv -> WifiDeviceKind.TV_ANDROID
            "googlecast" in type -> WifiDeviceKind.CAST
            else -> WifiDeviceKind.OTHER
        }
        val name = txt["fn"]?.takeIf { it.isNotBlank() } ?: r.info.serviceName.substringBefore('@').ifBlank { ip }
        val model = txt["md"] ?: txt["model"] ?: txt["am"]
        val manufacturer = txt["manufacturer"] ?: if (kind == WifiDeviceKind.CAST) "Google Cast" else null
        return DiscoveredDevice(
            id = if (atv) "androidtv-${LanMath.safeId(r.info.serviceName)}" else "mdns-${LanMath.safeId(ip)}",
            ip = ip, name = name, kind = kind, protocol = if (atv) WifiProtocol.ANDROID_TV else WifiProtocol.NONE,
            port = if (atv) (r.info.port.takeIf { it > 0 } ?: AtvRemoteProtocol.PORT) else r.info.port, manufacturer = manufacturer, model = model, sources = setOf(DiscoverySource.MDNS),
        )
    }

    private fun defaultName(kind: WifiDeviceKind, manufacturer: String?): String? = when (kind) {
        WifiDeviceKind.TV_SAMSUNG -> "Samsung TV"
        WifiDeviceKind.TV_LG -> "LG TV"
        WifiDeviceKind.TV_ROKU -> "Roku"
        else -> manufacturer
    }
}
