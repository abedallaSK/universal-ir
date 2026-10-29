package com.universalir

import com.universalir.data.wifi.GreeCrypto
import com.universalir.data.wifi.GreePackets
import com.universalir.data.wifi.LanMath
import com.universalir.data.wifi.Ssdp
import com.universalir.data.wifi.WifiDeviceKind
import com.universalir.data.wifi.WifiProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiProtocolTest {

    @Test fun greeEcbRoundTrip() {
        val plain = """{"t":"scan"}"""
        val pack = GreeCrypto.encrypt(plain)
        assertEquals(plain, GreeCrypto.decrypt(pack))
        // AES block = 16 bytes; 12 bytes of plaintext pad to one block → 24 base64 chars.
        assertEquals(24, pack.length)
    }

    @Test fun greeEcbWithDeviceKey() {
        val key = "0123456789abcdef"
        val plain = """{"opt":["Pow"],"p":[1],"t":"cmd"}"""
        assertEquals(plain, GreeCrypto.decrypt(GreeCrypto.encrypt(plain, key), key))
        assertTrue(runCatching { GreeCrypto.decrypt(GreeCrypto.encrypt(plain, key)) }.getOrNull() != plain)
    }

    @Test fun greeGcmRoundTrip() {
        val plain = """{"mac":"f4911e000000","t":"bind","uid":0}"""
        val (pack, tag) = GreeCrypto.encryptGcm(plain)
        assertEquals(plain, GreeCrypto.decryptGcm(pack, tag))
    }

    @Test fun greeScanReplyIsParsed() {
        val dev = """{"t":"dev","cid":"f4911e7f0bf1","bc":"gree","brand":"gree","catalog":"gree","mac":"f4911e7f0bf1","mid":"10001","model":"gree","name":"1e7f0bf1","series":"gree","ver":"V1.1.13"}"""
        val datagram = """{"t":"pack","i":1,"uid":0,"cid":"f4911e7f0bf1","tcid":"","pack":"${GreeCrypto.encrypt(dev)}"}"""
        val info = GreePackets.parseScan(datagram)
        assertNotNull(info)
        assertEquals("f4911e7f0bf1", info!!.mac)
        assertEquals("gree", info.brand)
        assertFalse(info.gcm)
    }

    @Test fun greeStatusAndBindReplies() {
        val key = "abcdefghijklmnop"
        val bindPlain = """{"t":"bindok","mac":"aa","key":"$key","r":200}"""
        val bind = """{"t":"pack","pack":"${GreeCrypto.encrypt(bindPlain)}"}"""
        assertEquals(key, GreePackets.parseBindKey(GreePackets.openPack(bind, GreeCrypto.GENERIC_KEY)!!))

        val dat = """{"t":"dat","mac":"aa","r":200,"cols":["Pow","Mod","SetTem","WdSpd","TemUn","SwUpDn"],"dat":[1,4,27,3,0,1]}"""
        val datagram = """{"t":"pack","pack":"${GreeCrypto.encrypt(dat, key)}"}"""
        val status = GreePackets.parseStatus(GreePackets.openPack(datagram, key)!!)!!
        assertTrue(status.power); assertEquals(4, status.mode); assertEquals(27, status.temperature); assertEquals(3, status.fan); assertTrue(status.swing)
    }

    @Test fun greeCommandEnvelopeDecryptsToCommand() {
        val key = "abcdefghijklmnop"
        val req = GreePackets.commandRequest("aabbcc", key, gcm = false, values = linkedMapOf("Pow" to 1, "SetTem" to 22))
        val outer = Json.parseToJsonElement(req) as JsonObject
        assertEquals("aabbcc", outer["tcid"]!!.jsonPrimitive.content)
        val inner = GreePackets.openPack(req, key)!!
        assertEquals("cmd", inner["t"]!!.jsonPrimitive.content)
        assertEquals("""["Pow","SetTem"]""", inner["opt"].toString())
        assertEquals("[1,22]", inner["p"].toString())
    }

    @Test fun ssdpHeadersParsed() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.40:7676/smp_15_\r\n" +
            "SERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0\r\nST: urn:samsung.com:device:RemoteControlReceiver:1\r\n" +
            "USN: uuid:0ee6b280-00fa-1000-b6b4-f877b8a1c6a8::urn:samsung.com:device:RemoteControlReceiver:1\r\n\r\n"
        val r = Ssdp.parse(reply)!!
        assertEquals("http://192.168.1.40:7676/smp_15_", r.location)
        assertEquals("urn:samsung.com:device:RemoteControlReceiver:1", r.st)
        assertEquals("0ee6b280-00fa-1000-b6b4-f877b8a1c6a8", r.uuid)
        assertEquals(7676, Ssdp.portOf(r.location))
        assertEquals(WifiDeviceKind.TV_SAMSUNG to WifiProtocol.SAMSUNG_TIZEN, Ssdp.classify(r, null))
    }

    @Test fun ssdpClassifiesRokuAndLg() {
        val roku = Ssdp.parse("HTTP/1.1 200 OK\r\nST: roku:ecp\r\nLOCATION: http://10.0.0.5:8060/\r\nUSN: uuid:roku:ecp:X00000000001\r\n\r\n")!!
        assertEquals(WifiProtocol.ROKU_ECP, Ssdp.classify(roku, null).second)
        val lg = Ssdp.parse("HTTP/1.1 200 OK\nST: urn:lge-com:service:webos-second-screen:1\nSERVER: WebOS/4.1.0 UPnP/1.0\n\n")!!
        assertEquals(WifiDeviceKind.TV_LG, Ssdp.classify(lg, null).first)
        assertNull(Ssdp.parse("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n"))
    }

    @Test fun deviceDescriptionParsed() {
        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <friendlyName>[TV] Living Room</friendlyName><manufacturer>Samsung Electronics</manufacturer>
            <modelName>QE55Q80B</modelName></device></root>"""
        val d = Ssdp.parseDescription(xml)
        assertEquals("[TV] Living Room", d.friendlyName)
        assertEquals("Samsung Electronics", d.manufacturer)
        assertEquals("QE55Q80B", d.modelName)
    }

    @Test fun lanMath() {
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 255.toByte()), LanMath.broadcast(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), 24))
        assertArrayEquals(byteArrayOf(10, 0, 3, 255.toByte()), LanMath.broadcast(byteArrayOf(10, 0, 2, 7), 23))
        assertTrue(LanMath.isPrivateIpv4("192.168.0.10"))
        assertTrue(LanMath.isPrivateIpv4("172.20.1.1"))
        assertFalse(LanMath.isPrivateIpv4("8.8.8.8"))
        assertFalse(LanMath.isPrivateIpv4("tv.example.com"))
    }
}
