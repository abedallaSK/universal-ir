package com.universalir.data.wifi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal object LanHttp {
    /** Plain HTTP for Roku ECP and UPnP descriptions. Returns (code, body); throws on network errors. */
    suspend fun request(url: String, method: String = "GET", timeoutMs: Int = 2500, maxBytes: Int = 64 * 1024): Pair<Int, String> = runInterruptible(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.requestMethod = method; c.useCaches = false
            if (method == "POST") { c.doOutput = true; c.setFixedLengthStreamingMode(0); c.outputStream.close() }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readUpTo(maxBytes), Charsets.UTF_8) } ?: ""
            code to body
        } finally { c.disconnect() }
    }

    /**
     * WebSocket client for TVs on the LAN. Samsung (8002) and LG (3001) use self-signed certificates, so this one
     * client trusts any certificate, but only for private IPv4 literals. It is never installed globally.
     */
    val tvClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).writeTimeout(5, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { host, _ -> LanMath.isPrivateIpv4(host) }
            .build()
    }
}

/**
 * HTTPS JSON calls to AC makers' cloud services (Electra Smart, Sensibo) with the user's own credentials.
 * Uses the platform's normal certificate validation: nothing is trusted blindly here.
 */
internal object CloudHttp {
    suspend fun send(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 12_000,
        maxBytes: Int = 512 * 1024,
    ): Pair<Int, String> = runInterruptible(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.requestMethod = method; c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.doOutput = true; c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readUpTo(maxBytes), Charsets.UTF_8) } ?: ""
            code to text
        } finally { c.disconnect() }
    }
}

private fun InputStream.readUpTo(max: Int): ByteArray {
    val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
    while (out.size() < max) { val n = read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
    return out.toByteArray()
}

/** A WebSocket bridged to coroutines: messages arrive on [incoming]; the channel closes when the socket does. */
internal class WsSession private constructor() {
    val incoming = Channel<String>(Channel.UNLIMITED)
    private lateinit var ws: WebSocket
    @Volatile var isOpen = false
        private set

    fun send(text: String): Boolean = isOpen && ws.send(text)

    /** Next message, or null on timeout or when the socket closed (check [isOpen]). */
    suspend fun receive(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) { incoming.receiveCatching().getOrNull() }

    fun close() { isOpen = false; runCatching { ws.close(1000, null) }; incoming.close() }

    companion object {
        suspend fun connect(client: OkHttpClient, url: String, timeoutMs: Long = 5000): WsSession {
            val opened = CompletableDeferred<Unit>()
            val s = WsSession()
            s.ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { s.isOpen = true; opened.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) { s.incoming.trySend(text) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { s.isOpen = false; s.incoming.close(); webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { s.isOpen = false; s.incoming.close() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    s.isOpen = false
                    if (!opened.isCompleted) opened.completeExceptionally(t) else s.incoming.close(t)
                }
            })
            val ok = try {
                withTimeoutOrNull(timeoutMs) { opened.await(); true }
            } catch (e: Throwable) {
                s.ws.cancel(); throw e
            }
            // A plain exception (not TimeoutCancellationException) so callers report it instead of treating it as cancellation.
            if (ok == null) { s.ws.cancel(); throw SocketTimeoutException("WebSocket open timed out: $url") }
            return s
        }
    }
}
