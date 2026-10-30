package com.universalir.data.wifi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TLS for Android TV Remote v2: presents this app's client certificate and accepts the TV's self-signed certificate.
 * Only ever used for sockets to private IPv4 literals (checked in [open]); never installed globally.
 */
internal object AtvTls {
    fun context(keys: AtvKeys): SSLContext {
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(keys.certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = keys.privateKey
        }
        val trustTv = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(km), arrayOf(trustTv), SecureRandom()) }
    }

    /** Blocking: connects and completes the TLS handshake. */
    fun open(ctx: SSLContext, ip: String, port: Int, readTimeoutMs: Int): SSLSocket {
        if (!LanMath.isPrivateIpv4(ip)) throw WifiException(WifiError.UNSUPPORTED, "not a local IPv4 address")
        val s = ctx.socketFactory.createSocket() as SSLSocket
        try {
            // TVs waking from standby and Wi-Fi power saving can take several seconds to answer.
            s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = HANDSHAKE_READ_MS
            s.startHandshake()
            s.soTimeout = readTimeoutMs
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        return s
    }

    private const val ALIAS = "universalir"
    const val CONNECT_TIMEOUT_MS = 8_000
    private const val HANDSHAKE_READ_MS = 10_000
}

/**
 * One pairing session on port 6467. [start] makes the TV show a 6-character code; [finish] checks it and sends the
 * secret. A code that fails the local check byte leaves the session open, so the user can retype it.
 */
class AndroidTvPairing internal constructor(private val ip: String, private val keys: suspend () -> AtvKeys, private val clientName: String) {
    private val mutex = Mutex()
    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    val isOpen: Boolean get() = socket?.isClosed == false

    suspend fun start(): WifiResult<Unit> = mutex.withLock {
        closeLocked()
        val k = try { keys() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return@withLock WifiResult.Err(WifiError.PROTOCOL, "client key: ${e.message}")
        }
        val r = wifiCatching {
            runInterruptible(Dispatchers.IO) {
                val s = AtvTls.open(AtvTls.context(k), ip, AtvPairingProtocol.PORT, 10_000)
                socket = s; input = BufferedInputStream(s.inputStream); output = s.outputStream
                exchange(AtvPairingProtocol.pairingRequest(clientName), AtvPairingProtocol.F_PAIRING_REQUEST_ACK)
                exchange(AtvPairingProtocol.options(), AtvPairingProtocol.F_OPTIONS)
                exchange(AtvPairingProtocol.configuration(), AtvPairingProtocol.F_CONFIGURATION_ACK)
            }
        }
        if (r is WifiResult.Err) closeLocked()
        r
    }

    /** [code] as shown on the TV. Err(AUTH) for a wrong code ([isOpen] tells whether the user may simply retype it). */
    suspend fun finish(code: String): WifiResult<Unit> = mutex.withLock {
        val s = socket ?: return@withLock WifiResult.Err(WifiError.NOT_PAIRED, "pairing not started")
        val normalized = AtvPairingProtocol.normalizeCode(code)
        if (!AtvPairingProtocol.isWellFormedCode(normalized)) return@withLock WifiResult.Err(WifiError.AUTH, "code")
        val r = wifiCatching {
            val server = s.session.peerCertificates.firstOrNull() as? X509Certificate ?: throw WifiException(WifiError.PROTOCOL, "no TV certificate")
            val serverKey = server.publicKey as? RSAPublicKey ?: throw WifiException(WifiError.PROTOCOL, "TV key is not RSA")
            val clientKey = keys().publicKey
            val hash = AtvPairingProtocol.secretHash(clientKey.modulus, clientKey.publicExponent, serverKey.modulus, serverKey.publicExponent, normalized)
            if (!AtvPairingProtocol.codeMatches(hash, normalized)) throw WifiException(WifiError.AUTH, "code")
            runInterruptible(Dispatchers.IO) { exchange(AtvPairingProtocol.secret(hash), AtvPairingProtocol.F_SECRET_ACK) }
        }
        when {
            r is WifiResult.Ok -> closeLocked()
            r is WifiResult.Err && r.error == WifiError.AUTH && r.detail == "code" -> Unit // keep the session for a retry
            else -> closeLocked()
        }
        r
    }

    fun close() { closeLocked() }

    private fun closeLocked() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    /** Blocking request/response; non-200 status maps to AUTH (bad secret) or PROTOCOL. */
    private fun exchange(message: ByteArray, expect: Int) {
        val out = output ?: throw WifiException(WifiError.UNREACHABLE, "closed")
        val inp = input ?: throw WifiException(WifiError.UNREACHABLE, "closed")
        out.write(AtvFraming.frame(message)); out.flush()
        val reply = AtvPairingProtocol.parseReply(AtvFraming.read(inp))
        when (reply.status) {
            AtvPairingProtocol.STATUS_OK -> Unit
            AtvPairingProtocol.STATUS_BAD_SECRET -> throw WifiException(WifiError.AUTH, "rejected")
            else -> throw WifiException(WifiError.PROTOCOL, "status ${reply.status}")
        }
        if (reply.kind != expect) throw WifiException(WifiError.PROTOCOL, "unexpected pairing message ${reply.kind}")
    }
}

/**
 * Android TV / Google TV remote (protocol v2, port 6466). Requires a prior pairing ([AndroidTvPairing]); without it the
 * TV refuses the TLS handshake and [connect] returns [WifiError.NOT_PAIRED]. The TV pings every ~5 s; we answer, and
 * treat 16 s of silence as a dropped connection (as androidtvremote2 does).
 */
class AndroidTvController(
    private val device: SavedWifiDevice,
    private val keys: suspend () -> AtvKeys,
) : TvController {
    private val mutex = Mutex()
    private val writeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = device.port.takeIf { it > 0 && it != AtvPairingProtocol.PORT } ?: AtvRemoteProtocol.PORT
    @Volatile private var paired = device.paired
    @Volatile private var socket: SSLSocket? = null
    private var reader: Job? = null
    @Volatile private var features = AtvRemoteProtocol.FEATURES
    private val _status = MutableStateFlow(TvStatus())

    override val supported: Set<TvKey> = AtvRemoteProtocol.KEYCODES.keys
    override val apps: Set<TvApp> = AtvRemoteProtocol.APP_LINKS.keys
    override val status: StateFlow<TvStatus> = _status.asStateFlow()

    /** Called after a successful pairing so the next [connect] tries the remote port. */
    fun markPaired() { paired = true }

    override suspend fun connect(onPairing: () -> Unit): WifiResult<Unit> = mutex.withLock { openLocked() }

    private suspend fun openLocked(): WifiResult<Unit> {
        if (socket?.isClosed == false && reader?.isActive == true) return WifiResult.Ok(Unit)
        if (!paired) return WifiResult.Err(WifiError.NOT_PAIRED)
        dropLocked()
        val k = try { keys() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return WifiResult.Err(WifiError.PROTOCOL, "client key: ${e.message}")
        }
        val s = try {
            runInterruptible(Dispatchers.IO) { AtvTls.open(AtvTls.context(k), device.ip, port, IDLE_TIMEOUT_MS) }
        } catch (e: CancellationException) { throw e } catch (e: SSLException) {
            // The TV rejects certificates it hasn't paired with (or that were reset on the TV side).
            return WifiResult.Err(WifiError.NOT_PAIRED, e.message)
        } catch (e: Exception) {
            return WifiResult.Err(errorOf(e), e.message)
        }
        socket = s
        val ready = CompletableDeferred<Unit>()
        reader = scope.launch { readLoop(s, ready) }
        val ok = try { withTimeoutOrNull(HANDSHAKE_MS) { ready.await(); true } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            dropLocked()
            return if (e is SSLException) WifiResult.Err(WifiError.NOT_PAIRED, e.message) else WifiResult.Err(errorOf(e), e.message)
        }
        // Some TVs never send RemoteStart; an open, configured socket is good enough.
        if (ok == null && (s.isClosed || reader?.isActive != true)) { dropLocked(); return WifiResult.Err(WifiError.TIMEOUT, "no remote handshake") }
        return WifiResult.Ok(Unit)
    }

    private fun readLoop(s: SSLSocket, ready: CompletableDeferred<Unit>) {
        try {
            val input = BufferedInputStream(s.inputStream)
            while (!s.isClosed) {
                when (val m = AtvRemoteProtocol.parse(AtvFraming.read(input))) {
                    is AtvRemoteProtocol.Incoming.Configure -> {
                        features = if (m.features != 0) AtvRemoteProtocol.FEATURES and m.features else AtvRemoteProtocol.FEATURES
                        write(s, AtvRemoteProtocol.configure(features))
                    }
                    is AtvRemoteProtocol.Incoming.SetActive -> write(s, AtvRemoteProtocol.setActive(features))
                    is AtvRemoteProtocol.Incoming.Ping -> write(s, AtvRemoteProtocol.pingResponse(m.val1))
                    is AtvRemoteProtocol.Incoming.Start -> { _status.update { it.copy(on = m.started) }; ready.complete(Unit) }
                    is AtvRemoteProtocol.Incoming.Volume -> _status.update { it.copy(volume = m.level ?: it.volume, volumeMax = m.max ?: it.volumeMax, muted = m.muted ?: it.muted) }
                    is AtvRemoteProtocol.Incoming.CurrentApp -> _status.update { it.copy(app = m.packageName) }
                    AtvRemoteProtocol.Incoming.Error, AtvRemoteProtocol.Incoming.Other -> Unit
                }
            }
        } catch (e: Exception) {
            ready.completeExceptionally(e)
        } finally {
            runCatching { s.close() }
            if (socket === s) socket = null
        }
    }

    private fun write(s: SSLSocket, message: ByteArray) {
        synchronized(writeLock) {
            val out = s.outputStream
            out.write(AtvFraming.frame(message)); out.flush()
        }
    }

    private suspend fun send(message: ByteArray): WifiResult<Unit> = mutex.withLock {
        repeat(2) { attempt ->
            val open = openLocked()
            if (open is WifiResult.Err) return@withLock open
            val s = socket
            val r = if (s == null) WifiResult.Err(WifiError.UNREACHABLE, "closed") else wifiCatching { withContext(Dispatchers.IO) { write(s, message) } }
            if (r is WifiResult.Ok || attempt == 1) return@withLock r
            dropLocked()
        }
        WifiResult.Err(WifiError.UNREACHABLE)
    }

    override suspend fun press(key: TvKey): WifiResult<Unit> {
        val code = AtvRemoteProtocol.KEYCODES[key] ?: return WifiResult.Err(WifiError.UNSUPPORTED)
        return send(AtvRemoteProtocol.keyInject(code))
    }

    override suspend fun launch(app: TvApp): WifiResult<Unit> {
        val link = AtvRemoteProtocol.APP_LINKS[app] ?: return WifiResult.Err(WifiError.UNSUPPORTED)
        return send(AtvRemoteProtocol.appLink(link))
    }

    private fun dropLocked() {
        reader?.cancel(); reader = null
        runCatching { socket?.close() }; socket = null
    }

    override fun close() { dropLocked(); scope.cancel() }

    private companion object {
        const val IDLE_TIMEOUT_MS = 16_000
        const val HANDSHAKE_MS = 10_000L
    }
}
