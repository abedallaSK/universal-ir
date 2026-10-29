package com.universalir.data.wifi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Pure JVM: builds the self-signed RSA client certificate the Android TV Remote protocol needs. Android's public SDK has
// no X.509 builder (BouncyCastle is hidden), so this writes the small DER structure by hand.

/** Minimal DER encoder for the handful of ASN.1 types a certificate needs. */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))
    fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun nul() = byteArrayOf(0x05, 0x00)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)
    fun explicit(tagNumber: Int, content: ByteArray) = tlv(0xA0 or tagNumber, content)

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = p
            stack += (v and 0x7F).toInt()
            v = v shr 7
            while (v > 0) { stack += ((v and 0x7F) or 0x80).toInt(); v = v shr 7 }
            for (i in stack.indices.reversed()) out.write(stack[i])
        }
        return tlv(0x06, out.toByteArray())
    }

    /** UTCTime before 2050, GeneralizedTime from 2050 (RFC 5280 4.1.2.5). */
    fun time(d: Date): ByteArray {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = d }
        val utc = cal.get(java.util.Calendar.YEAR) < 2050
        val f = SimpleDateFormat(if (utc) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(if (utc) 0x17 else 0x18, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}

object SelfSignedCertificate {
    private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val COMMON_NAME = "2.5.4.3"

    /** A v3 certificate without extensions, issuer = subject = CN=[commonName], signed SHA256withRSA by [keys]. */
    fun create(keys: KeyPair, commonName: String, notBefore: Date, notAfter: Date, serial: BigInteger): X509Certificate {
        val algorithm = Der.seq(Der.oid(SHA256_WITH_RSA), Der.nul())
        val name = Der.seq(Der.set(Der.seq(Der.oid(COMMON_NAME), Der.utf8(commonName))))
        val tbs = Der.seq(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))), // v3
            Der.integer(serial),
            algorithm,
            name,
            Der.seq(Der.time(notBefore), Der.time(notAfter)),
            name,
            keys.public.encoded, // SubjectPublicKeyInfo, already DER
        )
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(tbs); sign() }
        val der = Der.seq(tbs, algorithm, Der.bitString(sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }
}

/** This app's Android TV Remote identity: an RSA-2048 key and its self-signed certificate. */
class AtvKeys(val certificate: X509Certificate, val privateKey: PrivateKey) {
    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    fun toData() = AtvIdentityData(
        Base64.getEncoder().encodeToString(certificate.encoded),
        Base64.getEncoder().encodeToString(privateKey.encoded),
    )

    companion object {
        /** Generates a new identity (≈0.2–2 s on a phone: call off the main thread). */
        fun generate(random: SecureRandom = SecureRandom()): AtvKeys {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val day = 24L * 3600 * 1000
            val cert = SelfSignedCertificate.create(
                kp, "Universal IR Remote",
                notBefore = Date(now - day), notAfter = Date(now + 20 * 365 * day),
                serial = BigInteger(63, random).add(BigInteger.ONE),
            )
            return AtvKeys(cert, kp.private)
        }

        fun fromData(d: AtvIdentityData): AtvKeys? = runCatching {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(d.certDer))) as X509Certificate
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(d.keyPkcs8)))
            AtvKeys(cert, key)
        }.getOrNull()
    }
}
