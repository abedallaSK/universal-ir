package com.universalir

import com.universalir.data.wifi.AtvFraming
import com.universalir.data.wifi.AtvKeys
import com.universalir.data.wifi.AtvPairingProtocol
import com.universalir.data.wifi.AtvRemoteProtocol
import com.universalir.data.wifi.ProtoMessage
import com.universalir.data.wifi.ProtoWriter
import com.universalir.data.wifi.TvKey
import com.universalir.data.wifi.Varint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger

/**
 * Byte vectors are the protobuf encodings of the messages androidtvremote2 / androidtv-remote send (polo.proto,
 * remotemessage.proto); the pairing-secret vector was computed with the reference's exact hashing lines in Python.
 */
class AndroidTvProtocolTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun varintsMatchProtobufSpec() {
        assertArrayEquals(hex("00"), Varint.encode(0))
        assertArrayEquals(hex("01"), Varint.encode(1))
        assertArrayEquals(hex("96 01"), Varint.encode(150))
        assertArrayEquals(hex("AC 02"), Varint.encode(300))
        assertArrayEquals(hex("C8 01"), Varint.encode(200))
        assertArrayEquals(hex("FF FF FF FF FF FF FF FF FF 01"), Varint.encode(-1))
        assertEquals(300L to 2, Varint.decode(hex("AC 02"), 0))
        assertNull(Varint.decode(hex("AC"), 0))
    }

    @Test fun writerAndReaderRoundTrip() {
        val bytes = ProtoWriter().varint(1, 150).string(2, "testing").message(3) { bool(1, true) }.toByteArray()
        // Classic protobuf docs example "08 96 01" and "12 07 74 65 73 74 69 6e 67".
        assertArrayEquals(hex("08 96 01 12 07 74 65 73 74 69 6E 67 1A 02 08 01"), bytes)
        val m = ProtoMessage.parse(bytes)
        assertEquals(150, m.int(1))
        assertEquals("testing", m.string(2))
        assertEquals(true, m.message(3)!!.bool(1))
        assertNull(m.int(9))
    }

    @Test fun pairingRequest() {
        val b = AtvPairingProtocol.pairingRequest("test")
        // protocol_version=2, status=200, pairing_request{service_name="atvremote", client_name="test"}
        assertArrayEquals(hex("08 02 10 C8 01 52 11 0A 09 61 74 76 72 65 6D 6F 74 65 12 04 74 65 73 74"), b)
    }

    @Test fun pairingOptionsAndConfiguration() {
        // options{input_encodings{type=HEXADECIMAL(3), symbol_length=6}, preferred_role=INPUT(1)}
        assertArrayEquals(hex("08 02 10 C8 01 A2 01 08 0A 04 08 03 10 06 18 01"), AtvPairingProtocol.options())
        // configuration{encoding{3, 6}, client_role=INPUT(1)}
        assertArrayEquals(hex("08 02 10 C8 01 F2 01 08 0A 04 08 03 10 06 10 01"), AtvPairingProtocol.configuration())
        val secret = AtvPairingProtocol.secret(ByteArray(2) { 0x7F })
        assertArrayEquals(hex("08 02 10 C8 01 C2 02 04 0A 02 7F 7F"), secret)
    }

    @Test fun pairingRepliesParsed() {
        // A TV's PairingRequestAck: version 2, status 200, field 11 {server_name="TV"}.
        val ack = AtvPairingProtocol.parseReply(hex("08 02 10 C8 01 5A 04 0A 02 54 56"))
        assertEquals(200, ack.status)
        assertEquals(AtvPairingProtocol.F_PAIRING_REQUEST_ACK, ack.kind)
        val bad = AtvPairingProtocol.parseReply(hex("08 02 10 92 03"))
        assertEquals(AtvPairingProtocol.STATUS_BAD_SECRET, bad.status)
        assertNull(bad.kind)
        // ConfigurationAck is an empty message: field 31, length 0.
        assertEquals(AtvPairingProtocol.F_CONFIGURATION_ACK, AtvPairingProtocol.parseReply(hex("08 02 10 C8 01 FA 01 00")).kind)
    }

    @Test fun pairingSecretMatchesReference() {
        // Python: h.update(bytes.fromhex(f"{cm:X}")); h.update(bytes.fromhex(f"0{ce:X}")); … h.update(bytes.fromhex(code[2:]))
        val hash = AtvPairingProtocol.secretHash(
            BigInteger("C0FFEE11", 16), BigInteger.valueOf(65537),
            BigInteger("ABCDEF0123", 16), BigInteger.valueOf(3),
            "B31A2B",
        )
        assertArrayEquals(hex("B3EEF74BCDDC04DC567223DDE620BAE9655BA2107D0F468447D8E9C82CFA6195"), hash)
        assertTrue(AtvPairingProtocol.codeMatches(hash, "B31A2B"))
        assertTrue(AtvPairingProtocol.codeMatches(hash, "b31a2b"))
        assertFalse(AtvPairingProtocol.codeMatches(hash, "A31A2B"))
    }

    @Test fun codeInputNormalization() {
        assertEquals("AB12CD", AtvPairingProtocol.normalizeCode("ab 12-cd99"))
        assertEquals("ABCDEF", AtvPairingProtocol.normalizeCode("xyzabcdef"))
        assertTrue(AtvPairingProtocol.isWellFormedCode("0A1B2C"))
        assertFalse(AtvPairingProtocol.isWellFormedCode("0A1B2"))
        assertFalse(AtvPairingProtocol.isWellFormedCode("0A1B2G"))
    }

    @Test fun remoteMessages() {
        // remote_key_inject{key_code=KEYCODE_POWER(26), direction=SHORT(3)}
        assertArrayEquals(hex("52 04 08 1A 10 03"), AtvRemoteProtocol.keyInject(AtvRemoteProtocol.KEYCODES.getValue(TvKey.POWER)))
        // remote_ping_response{val1=5}
        assertArrayEquals(hex("4A 02 08 05"), AtvRemoteProtocol.pingResponse(5))
        // remote_app_link_launch_request{app_link="netflix://"} — field 90 → tag D2 05
        assertArrayEquals(hex("D2 05 0C 0A 0A") + "netflix://".toByteArray(), AtvRemoteProtocol.appLink("netflix://"))
        // remote_set_active{active=611}
        assertArrayEquals(hex("12 03 08 E3 04"), AtvRemoteProtocol.setActive(611))
        assertEquals(611, AtvRemoteProtocol.FEATURES)
        val configure = ProtoMessage.parse(AtvRemoteProtocol.configure(611)).message(1)!!
        assertEquals(611, configure.int(1))
        assertEquals("atvremote", configure.message(2)!!.string(5))
    }

    @Test fun remoteIncomingParsed() {
        assertEquals(AtvRemoteProtocol.Incoming.Ping(7), AtvRemoteProtocol.parse(hex("42 04 08 07 10 00")))
        assertEquals(AtvRemoteProtocol.Incoming.Start(true), AtvRemoteProtocol.parse(hex("C2 02 02 08 01")))
        // remote_set_volume_level{volume_max=100 (field 6), volume_level=12 (field 7), volume_muted=false (field 8)}
        assertEquals(AtvRemoteProtocol.Incoming.Volume(12, 100, false), AtvRemoteProtocol.parse(hex("92 03 06 30 64 38 0C 40 00")))
        // remote_configure{code1=622, device_info{model="M", vendor="V"}}
        val cfg = AtvRemoteProtocol.parse(hex("0A 0B 08 EE 04 12 06 0A 01 4D 12 01 56"))
        assertEquals(AtvRemoteProtocol.Incoming.Configure(622, "V", "M"), cfg)
        // remote_ime_key_inject{app_info{app_package="com.netflix"}}
        val pkg = "com.netflix".toByteArray()
        val ime = ProtoWriter().message(20) { message(1) { bytes(12, pkg) } }.toByteArray()
        assertEquals(AtvRemoteProtocol.Incoming.CurrentApp("com.netflix"), AtvRemoteProtocol.parse(ime))
    }

    @Test fun framing() {
        val msg = AtvRemoteProtocol.pingResponse(5)
        val framed = AtvFraming.frame(msg)
        assertArrayEquals(hex("04 4A 02 08 05"), framed)
        val input = ByteArrayInputStream(framed + framed)
        assertArrayEquals(msg, AtvFraming.read(input))
        assertArrayEquals(msg, AtvFraming.read(input))
    }

    @Test fun clientCertificateIsValidSelfSigned() {
        val keys = AtvKeys.generate()
        val cert = keys.certificate
        cert.checkValidity()
        cert.verify(cert.publicKey) // self-signed
        assertEquals(3, cert.version)
        assertEquals(2048, keys.publicKey.modulus.bitLength())
        val restored = AtvKeys.fromData(keys.toData())!!
        assertEquals(keys.publicKey.modulus, restored.publicKey.modulus)
        assertArrayEquals(keys.privateKey.encoded, restored.privateKey.encoded)
    }
}
