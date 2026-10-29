package com.universalir.data.wifi

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest

// Pure-JVM (no Android types) protobuf wire format and Android TV Remote v2 messages, so it is unit-testable.
// Message layouts follow polo.proto / remotemessage.proto as used by tronikos/androidtvremote2 (Apache-2.0)
// and louis49/androidtv-remote.

class ProtoException(message: String) : IOException(message)

/** Base-128 varints (protobuf wire type 0, also the frame length prefix of Android TV Remote). */
object Varint {
    fun encode(value: Long): ByteArray {
        val out = ByteArrayOutputStream(10)
        var x = value
        while (true) {
            if ((x and 0x7FL.inv()) == 0L) { out.write(x.toInt()); return out.toByteArray() }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    /** Decodes at [offset]; returns (value, next offset) or null when the buffer ends mid-varint. */
    fun decode(buf: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xFF
            i++
            if (shift >= 64) throw ProtoException("varint too long")
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to i
            shift += 7
        }
        return null
    }

    /** Reads one varint from a stream; throws [EOFException] at end of stream. */
    fun read(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("stream closed")
            if (shift >= 64) throw ProtoException("varint too long")
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
    }
}

/** Builds a protobuf message. Negative ints are written sign-extended (10 bytes) like protobuf int32. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter { tag(field, 0); out.write(Varint.encode(value)); return this }
    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())
    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1L else 0L)
    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2); out.write(Varint.encode(value.size.toLong())); out.write(value); return this
    }
    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(build).toByteArray())
    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) { out.write(Varint.encode(((field.toLong() shl 3) or wire.toLong()))) }
}

/** A parsed protobuf message: fields by number. Unknown wire types are rejected; fixed32/64 are kept as numbers. */
class ProtoMessage private constructor(private val fields: List<Field>) {
    class Field(val number: Int, val wire: Int, val value: Long, val data: ByteArray?)

    val numbers: Set<Int> get() = fields.mapTo(LinkedHashSet()) { it.number }
    fun has(n: Int) = fields.any { it.number == n }
    fun varint(n: Int): Long? = fields.lastOrNull { it.number == n && it.wire == 0 }?.value
    fun int(n: Int): Int? = varint(n)?.toInt()
    fun bool(n: Int): Boolean? = varint(n)?.let { it != 0L }
    fun bytes(n: Int): ByteArray? = fields.lastOrNull { it.number == n && it.wire == 2 }?.data
    fun string(n: Int): String? = bytes(n)?.let { String(it, Charsets.UTF_8) }
    fun message(n: Int): ProtoMessage? = bytes(n)?.let { parse(it) }

    companion object {
        fun parse(b: ByteArray): ProtoMessage {
            val list = ArrayList<Field>()
            var i = 0
            while (i < b.size) {
                val (key, afterKey) = Varint.decode(b, i) ?: throw ProtoException("truncated tag")
                i = afterKey
                val number = (key ushr 3).toInt()
                val wire = (key and 7L).toInt()
                if (number <= 0) throw ProtoException("bad field number")
                when (wire) {
                    0 -> {
                        val (v, next) = Varint.decode(b, i) ?: throw ProtoException("truncated varint")
                        i = next; list += Field(number, 0, v, null)
                    }
                    1 -> {
                        if (i + 8 > b.size) throw ProtoException("truncated fixed64")
                        list += Field(number, 1, littleEndian(b, i, 8), null); i += 8
                    }
                    2 -> {
                        val (len, next) = Varint.decode(b, i) ?: throw ProtoException("truncated length")
                        if (len < 0 || len > (b.size - next).toLong()) throw ProtoException("bad length")
                        val end = next + len.toInt()
                        list += Field(number, 2, len, b.copyOfRange(next, end)); i = end
                    }
                    5 -> {
                        if (i + 4 > b.size) throw ProtoException("truncated fixed32")
                        list += Field(number, 5, littleEndian(b, i, 4), null); i += 4
                    }
                    else -> throw ProtoException("unsupported wire type $wire")
                }
            }
            return ProtoMessage(list)
        }

        private fun littleEndian(b: ByteArray, at: Int, n: Int): Long {
            var v = 0L
            for (k in n - 1 downTo 0) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
            return v
        }
    }
}

/** Android TV Remote frames: varint length, then the protobuf message. */
object AtvFraming {
    const val MAX_FRAME = 64 * 1024

    fun frame(message: ByteArray): ByteArray = Varint.encode(message.size.toLong()) + message

    fun read(input: InputStream): ByteArray {
        val len = Varint.read(input)
        if (len < 0 || len > MAX_FRAME) throw ProtoException("frame too large: $len")
        val buf = ByteArray(len.toInt())
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("stream closed mid-frame")
            off += n
        }
        return buf
    }
}

/** Pairing protocol ("polo") on port 6467. */
object AtvPairingProtocol {
    const val PORT = 6467
    const val SERVICE_NAME = "atvremote"
    const val PROTOCOL_VERSION = 2
    const val STATUS_OK = 200
    const val STATUS_ERROR = 400
    const val STATUS_BAD_CONFIGURATION = 401
    const val STATUS_BAD_SECRET = 402

    // OuterMessage field numbers.
    const val F_PAIRING_REQUEST = 10
    const val F_PAIRING_REQUEST_ACK = 11
    const val F_OPTIONS = 20
    const val F_CONFIGURATION = 30
    const val F_CONFIGURATION_ACK = 31
    const val F_SECRET = 40
    const val F_SECRET_ACK = 41

    private const val ENCODING_HEXADECIMAL = 3
    private const val SYMBOL_LENGTH = 6
    private const val ROLE_INPUT = 1

    private fun outer(body: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().varint(1, PROTOCOL_VERSION).varint(2, STATUS_OK).apply(body).toByteArray()

    fun pairingRequest(clientName: String): ByteArray = outer { message(F_PAIRING_REQUEST) { string(1, SERVICE_NAME); string(2, clientName) } }

    fun options(): ByteArray = outer {
        message(F_OPTIONS) {
            message(1) { varint(1, ENCODING_HEXADECIMAL); varint(2, SYMBOL_LENGTH) } // input_encodings
            varint(3, ROLE_INPUT) // preferred_role
        }
    }

    fun configuration(): ByteArray = outer {
        message(F_CONFIGURATION) {
            message(1) { varint(1, ENCODING_HEXADECIMAL); varint(2, SYMBOL_LENGTH) } // encoding
            varint(2, ROLE_INPUT) // client_role
        }
    }

    fun secret(secret: ByteArray): ByteArray = outer { message(F_SECRET) { bytes(1, secret) } }

    /** A reply from the TV: its status and which phase message it carries (null if none). */
    data class Reply(val status: Int, val kind: Int?)

    fun parseReply(b: ByteArray): Reply {
        val m = ProtoMessage.parse(b)
        val status = m.int(2) ?: throw ProtoException("pairing reply without status")
        val kind = listOf(F_PAIRING_REQUEST_ACK, F_OPTIONS, F_CONFIGURATION_ACK, F_SECRET_ACK, F_PAIRING_REQUEST, F_CONFIGURATION, F_SECRET).firstOrNull { m.has(it) }
        return Reply(status, kind)
    }

    /** The code the TV shows: exactly 6 hexadecimal characters. */
    fun isWellFormedCode(code: String) = code.length == SYMBOL_LENGTH && code.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Keeps hex characters only, upper-cased, at most 6 — for the code input field. */
    fun normalizeCode(input: String): String = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(SYMBOL_LENGTH)

    /**
     * SHA-256 over client modulus, client exponent, server modulus, server exponent and the last 4 code characters,
     * exactly as androidtvremote2 does: `bytes.fromhex(f"{modulus:X}")` and `bytes.fromhex(f"0{exponent:X}")`.
     */
    fun secretHash(clientModulus: BigInteger, clientExponent: BigInteger, serverModulus: BigInteger, serverExponent: BigInteger, code: String): ByteArray {
        require(isWellFormedCode(code)) { "code must be 6 hex characters" }
        val h = MessageDigest.getInstance("SHA-256")
        h.update(hexBytes(clientModulus.toString(16)))
        h.update(hexBytes("0" + clientExponent.toString(16)))
        h.update(hexBytes(serverModulus.toString(16)))
        h.update(hexBytes("0" + serverExponent.toString(16)))
        h.update(hexBytes(code.substring(2)))
        return h.digest()
    }

    /** The first two code characters are a check byte: they must equal the first byte of [hash]. */
    fun codeMatches(hash: ByteArray, code: String): Boolean =
        isWellFormedCode(code) && (hash[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)

    internal fun hexBytes(hex: String): ByteArray {
        val h = if (hex.length % 2 == 1) "0$hex" else hex
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/** Remote protocol on port 6466 (RemoteMessage). */
object AtvRemoteProtocol {
    const val PORT = 6466

    // Feature bits announced in RemoteConfigure / RemoteSetActive.
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 2
    const val FEATURE_IME = 4
    const val FEATURE_POWER = 32
    const val FEATURE_VOLUME = 64
    const val FEATURE_APP_LINK = 512
    const val FEATURES = FEATURE_PING or FEATURE_KEY or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    const val DIRECTION_SHORT = 3

    /** Android KeyEvent codes (RemoteKeyCode). */
    val KEYCODES: Map<TvKey, Int> = linkedMapOf(
        TvKey.POWER to 26, TvKey.HOME to 3, TvKey.BACK to 4,
        TvKey.UP to 19, TvKey.DOWN to 20, TvKey.LEFT to 21, TvKey.RIGHT to 22, TvKey.OK to 23,
        TvKey.VOL_UP to 24, TvKey.VOL_DOWN to 25, TvKey.MUTE to 164,
        TvKey.CH_UP to 166, TvKey.CH_DOWN to 167,
        TvKey.PLAY_PAUSE to 85, TvKey.PLAY to 126, TvKey.PAUSE to 127, TvKey.NEXT to 87, TvKey.PREVIOUS to 88,
        TvKey.REWIND to 89, TvKey.FAST_FORWARD to 90,
        TvKey.MENU to 82, TvKey.SETTINGS to 176, TvKey.INPUT to 178, TvKey.INFO to 165, TvKey.CAPTIONS to 175, TvKey.GUIDE to 172,
        TvKey.RED to 183, TvKey.GREEN to 184, TvKey.YELLOW to 185, TvKey.BLUE to 186,
        TvKey.NUM_0 to 7, TvKey.NUM_1 to 8, TvKey.NUM_2 to 9, TvKey.NUM_3 to 10, TvKey.NUM_4 to 11,
        TvKey.NUM_5 to 12, TvKey.NUM_6 to 13, TvKey.NUM_7 to 14, TvKey.NUM_8 to 15, TvKey.NUM_9 to 16,
    )

    /** App links (as in the Home Assistant Android TV Remote docs). */
    val APP_LINKS: Map<TvApp, String> = linkedMapOf(
        TvApp.YOUTUBE to "https://www.youtube.com",
        TvApp.NETFLIX to "netflix://",
        TvApp.PRIME_VIDEO to "https://app.primevideo.com",
        TvApp.DISNEY_PLUS to "https://www.disneyplus.com",
    )

    // RemoteMessage field numbers.
    private const val F_CONFIGURE = 1
    private const val F_SET_ACTIVE = 2
    private const val F_ERROR = 3
    private const val F_PING_REQUEST = 8
    private const val F_PING_RESPONSE = 9
    private const val F_KEY_INJECT = 10
    private const val F_IME_KEY_INJECT = 20
    private const val F_START = 40
    private const val F_SET_VOLUME_LEVEL = 50
    private const val F_APP_LINK_LAUNCH = 90

    fun configure(features: Int): ByteArray = ProtoWriter().message(F_CONFIGURE) {
        varint(1, features) // code1
        message(2) { varint(3, 1); string(4, "1"); string(5, "atvremote"); string(6, "1.0.0") } // device_info
    }.toByteArray()

    fun setActive(features: Int): ByteArray = ProtoWriter().message(F_SET_ACTIVE) { varint(1, features) }.toByteArray()

    fun pingResponse(val1: Int): ByteArray = ProtoWriter().message(F_PING_RESPONSE) { varint(1, val1) }.toByteArray()

    fun keyInject(keyCode: Int, direction: Int = DIRECTION_SHORT): ByteArray =
        ProtoWriter().message(F_KEY_INJECT) { varint(1, keyCode); varint(2, direction) }.toByteArray()

    fun appLink(link: String): ByteArray = ProtoWriter().message(F_APP_LINK_LAUNCH) { string(1, link) }.toByteArray()

    sealed interface Incoming {
        data class Configure(val features: Int, val vendor: String?, val model: String?) : Incoming
        data class SetActive(val features: Int) : Incoming
        data class Ping(val val1: Int) : Incoming
        data class Start(val started: Boolean) : Incoming
        data class Volume(val level: Int?, val max: Int?, val muted: Boolean?) : Incoming
        data class CurrentApp(val packageName: String) : Incoming
        data object Error : Incoming
        data object Other : Incoming
    }

    fun parse(b: ByteArray): Incoming {
        val m = ProtoMessage.parse(b)
        m.message(F_PING_REQUEST)?.let { return Incoming.Ping(it.int(1) ?: 0) }
        m.message(F_CONFIGURE)?.let { c ->
            val info = c.message(2)
            return Incoming.Configure(c.int(1) ?: 0, info?.string(2), info?.string(1))
        }
        m.message(F_SET_ACTIVE)?.let { return Incoming.SetActive(it.int(1) ?: 0) }
        m.message(F_START)?.let { return Incoming.Start(it.bool(1) ?: false) }
        m.message(F_SET_VOLUME_LEVEL)?.let { return Incoming.Volume(it.int(7), it.int(6), it.bool(8)) }
        m.message(F_IME_KEY_INJECT)?.let { ime ->
            val pkg = ime.message(1)?.string(12)
            return if (pkg.isNullOrBlank()) Incoming.Other else Incoming.CurrentApp(pkg)
        }
        if (m.has(F_ERROR)) return Incoming.Error
        return Incoming.Other
    }
}
