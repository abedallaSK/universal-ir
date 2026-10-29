package com.universalir.data.wifi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Pure-JVM protocol helpers (no Android types) so they are unit-testable.

/** Gree "pack" encryption. Classic firmware: AES-128/ECB/PKCS5 + base64. Newer firmware: AES-128/GCM with a fixed IV/AAD. */
object GreeCrypto {
    const val GENERIC_KEY = "a3K8Bx%2r8Y7#xDh"
    const val GENERIC_GCM_KEY = "{yxAHAY_Lm6pbC/<"
    private val GCM_IV = byteArrayOf(0x54, 0x40, 0x78, 0x44, 0x49, 0x67, 0x5a, 0x51, 0x6c, 0x5e, 0x63, 0x13)
    private val GCM_AAD = "qualcomm-test".toByteArray()

    fun encrypt(plain: String, key: String = GENERIC_KEY): String {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return Base64.getEncoder().encodeToString(c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(pack: String, key: String = GENERIC_KEY): String {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return String(c.doFinal(Base64.getDecoder().decode(pack.trim())), Charsets.UTF_8)
    }

    /** Returns (pack, tag), both base64. */
    fun encryptGcm(plain: String, key: String = GENERIC_GCM_KEY): Pair<String, String> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), GCMParameterSpec(128, GCM_IV))
        c.updateAAD(GCM_AAD)
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder()
        return enc.encodeToString(out.copyOfRange(0, out.size - 16)) to enc.encodeToString(out.copyOfRange(out.size - 16, out.size))
    }

    fun decryptGcm(pack: String, tag: String, key: String = GENERIC_GCM_KEY): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), GCMParameterSpec(128, GCM_IV))
        c.updateAAD(GCM_AAD)
        val dec = Base64.getDecoder()
        return String(c.doFinal(dec.decode(pack.trim()) + dec.decode(tag.trim())), Charsets.UTF_8)
    }
}

/** Builds and parses Gree UDP datagrams (port 7000). */
object GreePackets {
    const val PORT = 7000
    const val SCAN = """{"t":"scan"}"""
    private val json = Json { ignoreUnknownKeys = true }

    data class ScanInfo(val mac: String, val name: String?, val brand: String?, val model: String?, val version: String?, val gcm: Boolean)

    fun envelope(mac: String, plain: String, key: String, gcm: Boolean, i: Int = 0): String = buildJsonObject {
        put("cid", "app"); put("i", i); put("t", "pack"); put("uid", 0); put("tcid", mac)
        if (gcm) { val (p, tag) = GreeCrypto.encryptGcm(plain, key); put("pack", p); put("tag", tag) }
        else put("pack", GreeCrypto.encrypt(plain, key))
    }.toString()

    fun bindRequest(mac: String, gcm: Boolean) =
        envelope(mac, """{"mac":"$mac","t":"bind","uid":0}""", if (gcm) GreeCrypto.GENERIC_GCM_KEY else GreeCrypto.GENERIC_KEY, gcm, i = 1)

    val STATUS_COLS = listOf("Pow", "Mod", "SetTem", "WdSpd", "TemUn", "SwUpDn")

    fun statusRequest(mac: String, key: String, gcm: Boolean): String {
        val cols = STATUS_COLS.joinToString(",") { "\"$it\"" }
        return envelope(mac, """{"cols":[$cols],"mac":"$mac","t":"status"}""", key, gcm)
    }

    fun commandRequest(mac: String, key: String, gcm: Boolean, values: Map<String, Int>): String {
        val opt = values.keys.joinToString(",") { "\"$it\"" }
        val p = values.values.joinToString(",")
        return envelope(mac, """{"opt":[$opt],"p":[$p],"t":"cmd"}""", key, gcm)
    }

    /** Decrypts the "pack" of a received datagram; tries [keys] in order. Returns null if nothing decrypts to JSON. */
    fun openPack(datagram: String, vararg keys: String): JsonObject? {
        val outer = runCatching { json.parseToJsonElement(datagram) as? JsonObject }.getOrNull() ?: return null
        val pack = (outer["pack"] as? JsonPrimitive)?.content ?: return null
        val tag = (outer["tag"] as? JsonPrimitive)?.content
        for (k in keys) {
            val plain = runCatching { if (tag != null) GreeCrypto.decryptGcm(pack, tag, k) else GreeCrypto.decrypt(pack, k) }.getOrNull() ?: continue
            val obj = runCatching { json.parseToJsonElement(plain) as? JsonObject }.getOrNull()
            if (obj != null) return obj
        }
        return null
    }

    fun isGcm(datagram: String): Boolean = runCatching { (json.parseToJsonElement(datagram) as JsonObject).containsKey("tag") }.getOrDefault(false)

    fun parseScan(datagram: String): ScanInfo? {
        val p = openPack(datagram, GreeCrypto.GENERIC_KEY, GreeCrypto.GENERIC_GCM_KEY) ?: return null
        if (p.str("t") != "dev") return null
        val mac = p.str("mac") ?: p.str("cid") ?: return null
        return ScanInfo(mac, p.str("name"), p.str("brand"), p.str("model"), p.str("ver"), isGcm(datagram))
    }

    fun parseBindKey(pack: JsonObject): String? = if (pack.str("t") == "bindok") pack.str("key") else null

    /** Reads a "dat" reply into a [GreeState]. */
    fun parseStatus(pack: JsonObject): GreeState? {
        if (pack.str("t") != "dat") return null
        val cols = (pack["cols"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: return null
        val dat = (pack["dat"] as? JsonArray)?.map { it.jsonPrimitive.intOrNull } ?: return null
        val v = cols.zip(dat).toMap()
        return GreeState(
            power = v["Pow"] == 1,
            mode = v["Mod"] ?: 1,
            temperature = v["SetTem"] ?: 24,
            fan = v["WdSpd"] ?: 0,
            swing = (v["SwUpDn"] ?: 0) != 0,
            fahrenheitDisplay = v["TemUn"] == 1,
        )
    }

    fun commandAccepted(pack: JsonObject): Boolean = pack.str("t") == "res" && (pack["r"] as? JsonPrimitive)?.intOrNull.let { it == null || it == 200 }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}

data class SsdpResponse(val headers: Map<String, String>) {
    val location get() = headers["location"]
    val server get() = headers["server"]
    val st get() = headers["st"] ?: headers["nt"]
    val usn get() = headers["usn"]
    /** The device UUID from USN ("uuid:xxxx::urn:…"), used as a stable id across IP changes. */
    val uuid get() = usn?.substringAfter("uuid:", "")?.substringBefore("::")?.takeIf { it.isNotBlank() }
}

data class DeviceDescription(val friendlyName: String?, val manufacturer: String?, val modelName: String?)

object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900
    val SEARCH_TARGETS = listOf(
        "ssdp:all", "roku:ecp", "urn:dial-multiscreen-org:service:dial:1",
        "urn:samsung.com:device:RemoteControlReceiver:1", "urn:lge-com:service:webos-second-screen:1",
    )

    fun mSearch(st: String, mx: Int = 2) =
        "M-SEARCH * HTTP/1.1\r\nHOST: $ADDRESS:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $st\r\nUSER-AGENT: Android/1 UPnP/1.1 UniversalIR/1\r\n\r\n"

    /** Parses an M-SEARCH reply or NOTIFY. Header names are lower-cased. Returns null for anything else. */
    fun parse(text: String): SsdpResponse? {
        val lines = text.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim() ?: return null
        if (!first.startsWith("HTTP/1.1 200", ignoreCase = true) && !first.startsWith("NOTIFY", ignoreCase = true)) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        return SsdpResponse(headers)
    }

    fun parseDescription(xml: String): DeviceDescription {
        fun tag(name: String) = Regex("<(?:\\w+:)?$name>\\s*([^<]*?)\\s*</(?:\\w+:)?$name>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.let(::unescapeXml)?.takeIf { it.isNotBlank() }
        return DeviceDescription(tag("friendlyName"), tag("manufacturer"), tag("modelName"))
    }

    private fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Port from a LOCATION url, or -1. */
    fun portOf(location: String?): Int = location?.let { Regex("://[^/:]+:(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1

    fun classify(r: SsdpResponse, d: DeviceDescription?): Pair<WifiDeviceKind, WifiProtocol> {
        val hay = listOfNotNull(r.server, r.st, r.usn, d?.manufacturer, d?.modelName, d?.friendlyName).joinToString(" ").lowercase()
        return when {
            "roku" in hay -> WifiDeviceKind.TV_ROKU to WifiProtocol.ROKU_ECP
            "remotecontrolreceiver" in hay || ("samsung" in hay && ("tizen" in hay || "tv" in hay || "dial" in hay)) -> WifiDeviceKind.TV_SAMSUNG to WifiProtocol.SAMSUNG_TIZEN
            "webos" in hay || "lge-com" in hay || ("lg electronics" in hay && "tv" in hay) -> WifiDeviceKind.TV_LG to WifiProtocol.LG_WEBOS
            "android" in hay && "tv" in hay -> WifiDeviceKind.TV_ANDROID to WifiProtocol.ANDROID_TV
            "dial" in hay || "chromecast" in hay -> WifiDeviceKind.CAST to WifiProtocol.NONE
            else -> WifiDeviceKind.OTHER to WifiProtocol.NONE
        }
    }
}

object LanMath {
    /** IPv4 directed broadcast for [address] with [prefix] bits, e.g. 192.168.1.20/24 → 192.168.1.255. */
    fun broadcast(address: ByteArray, prefix: Int): ByteArray {
        require(address.size == 4 && prefix in 0..32)
        val ip = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val bc = (ip and mask) or (mask.inv() and 0xFFFFFFFFL)
        return byteArrayOf((bc shr 24).toByte(), (bc shr 16).toByte(), (bc shr 8).toByte(), bc.toByte())
    }

    /** True for RFC1918 / link-local IPv4 literals; the trusting TLS client refuses anything else. */
    fun isPrivateIpv4(host: String): Boolean {
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
    }

    /** Stable, route-safe id fragment. */
    fun safeId(s: String) = s.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
}
