package com.universalir

import com.universalir.data.wifi.DaikinCodec
import com.universalir.data.wifi.ElectraCodec
import com.universalir.data.wifi.GreeAcController
import com.universalir.data.wifi.GreeState
import com.universalir.data.wifi.SensiboCodec
import com.universalir.data.wifi.Ssdp
import com.universalir.data.wifi.WifiAcCaps
import com.universalir.data.wifi.WifiAcMode
import com.universalir.data.wifi.WifiAcRules
import com.universalir.data.wifi.WifiAcState
import com.universalir.data.wifi.WifiDeviceKind
import com.universalir.data.wifi.WifiFan
import com.universalir.data.wifi.WifiProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WifiAcCodecTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    // ---------------------------------------------------------------- shared rules

    @Test fun coerceClampsToCapabilities() {
        val caps = WifiAcCaps(modes = listOf(WifiAcMode.COOL, WifiAcMode.HEAT), fans = listOf(WifiFan.LOW, WifiFan.HIGH), tempRange = 18..30, swing = false)
        val s = WifiAcRules.coerce(WifiAcState(true, WifiAcMode.DRY, 40, WifiFan.AUTO, swing = true), caps)
        assertEquals(WifiAcState(true, WifiAcMode.COOL, 30, WifiFan.LOW, swing = false), s)
    }

    @Test fun greeMapping() {
        val s = GreeAcController.fromGree(GreeState(power = true, mode = 4, temperature = 27, fan = 3, swing = true))
        assertEquals(WifiAcState(true, WifiAcMode.HEAT, 27, WifiFan.MEDIUM, true), s)
        assertEquals(2, GreeAcController.modeToGree(WifiAcMode.DRY))
        assertEquals(5, GreeAcController.fanToGree(WifiFan.HIGH))
    }

    // ---------------------------------------------------------------- Daikin

    @Test fun daikinControlInfoParsed() {
        val c = DaikinCodec.parse("ret=OK,pow=1,mode=3,adv=,stemp=23.0,shum=0,dt1=25.0,dt2=M,dt3=23.0,dt4=21.0,dt6=25.0,dh2=50,dh6=0,f_rate=5,f_dir=1,b_f_rate=A")
        assertTrue(DaikinCodec.isOk(c))
        assertEquals(WifiAcState(true, WifiAcMode.COOL, 23, WifiFan.MEDIUM, swing = true), DaikinCodec.state(c))
        val off = DaikinCodec.parse("ret=OK,pow=0,mode=4,stemp=21.0,shum=0,f_rate=A,f_dir=0")
        assertEquals(WifiAcState(false, WifiAcMode.HEAT, 21, WifiFan.AUTO, swing = false), DaikinCodec.state(off))
    }

    @Test fun daikinBasicInfoDecodesName() {
        val b = DaikinCodec.basic(DaikinCodec.parse("ret=OK,type=aircon,reg=eu,ver=1_2_51,pow=0,mac=A1B2C3D4E5F6,name=%4c%69%76%69%6e%67%20%52%6f%6f%6d"))
        assertEquals("Living Room", b.name)
        assertEquals("A1B2C3D4E5F6", b.mac)
        assertEquals(22.5, DaikinCodec.roomTemp(DaikinCodec.parse("ret=OK,htemp=22.5,hhum=-,otemp=30.0")))
        assertNull(DaikinCodec.roomTemp(DaikinCodec.parse("ret=OK,htemp=-")))
    }

    @Test fun daikinSetQuery() {
        val cur = DaikinCodec.parse("ret=OK,pow=1,mode=3,stemp=23.0,shum=0,dt2=M,dh2=50,dt6=--,dh6=--,f_rate=A,f_dir=0")
        assertEquals("pow=1&mode=4&stemp=22&shum=0&f_rate=7&f_dir=1",
            DaikinCodec.controlQuery(WifiAcState(true, WifiAcMode.HEAT, 22, WifiFan.HIGH, swing = true), cur))
        // Dry has no set point: the per-mode memory is reused.
        assertEquals("pow=1&mode=2&stemp=M&shum=50&f_rate=A&f_dir=0",
            DaikinCodec.controlQuery(WifiAcState(true, WifiAcMode.DRY, 22, WifiFan.AUTO, swing = false), cur))
        assertEquals("pow=0&mode=3&stemp=23&shum=0&f_rate=A&f_dir=0",
            DaikinCodec.controlQuery(WifiAcState(false, WifiAcMode.COOL, 23, WifiFan.AUTO, swing = false), cur))
    }

    // ---------------------------------------------------------------- Electra

    @Test fun electraRequests() {
        val otp = obj(ElectraCodec.sendOtp("2b95000012345678", "0501234567"))
        assertEquals("SEND_OTP", otp["cmd"]!!.jsonPrimitive.content)
        assertEquals(1, otp["pvdid"]!!.jsonPrimitive.content.toInt())
        assertEquals(99, otp["id"]!!.jsonPrimitive.content.toInt())
        assertEquals("0501234567", otp["data"]!!.jsonObject["phone"]!!.jsonPrimitive.content)

        val tel = obj(ElectraCodec.telemetry("SID1", "12345"))
        assertEquals("SID1", tel["sid"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(12345L), tel["data"]!!.jsonObject["id"])
        assertEquals("OPER,DIAG_L2", tel["data"]!!.jsonObject["commandName"]!!.jsonPrimitive.content)

        val imei = ElectraCodec.newImei(Random(1))
        assertTrue(imei.matches(Regex("2b950000\\d{8}")))
    }

    @Test fun electraTelemetryAndCommand() {
        val oper = """{"OPER":{"AC_MODE":"COOL","SPT":"24","FANSPD":"HIGH","VSWING":"ON","TURN_ON_OFF":"ON","AC_STSRC":"REMOTE"}}"""
        val diag = """{"DIAG_L2":{"I_RAT":"25","I_CALC_AT":"23"}}"""
        val resp = obj("""{"status":0,"data":{"res":0,"commandJson":{"OPER":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(oper))},"DIAG_L2":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(diag))}}}}""")
        assertTrue(ElectraCodec.isOk(resp))
        val o = ElectraCodec.oper(resp)!!
        assertEquals(WifiAcState(true, WifiAcMode.COOL, 24, WifiFan.HIGH, swing = true), ElectraCodec.state(o))
        assertEquals(23.0, ElectraCodec.roomTemp(resp)!!, 0.001)
        assertTrue(ElectraCodec.caps(o).swing)

        val next = ElectraCodec.applyState(o, WifiAcState(false, WifiAcMode.HEAT, 26, WifiFan.MEDIUM, swing = false))
        assertEquals("OFF", next["TURN_ON_OFF"]!!.jsonPrimitive.content)
        assertEquals("HEAT", next["AC_MODE"]!!.jsonPrimitive.content)
        assertEquals("26", next["SPT"]!!.jsonPrimitive.content)
        assertEquals("MED", next["FANSPD"]!!.jsonPrimitive.content)
        assertEquals("OFF", next["VSWING"]!!.jsonPrimitive.content)
        assertEquals("WI-FI", next["AC_STSRC"]!!.jsonPrimitive.content)

        val cmd = obj(ElectraCodec.command("SID", "7", next))
        val inner = obj(cmd["data"]!!.jsonObject["commandJson"]!!.jsonPrimitive.content)
        assertEquals("HEAT", inner["OPER"]!!.jsonObject["AC_MODE"]!!.jsonPrimitive.content)
    }

    @Test fun electraStandbyPowerModel() {
        val o = obj("""{"AC_MODE":"STBY","SPT":"22","FANSPD":"AUTO"}""")
        val s = ElectraCodec.state(o, lastMode = WifiAcMode.HEAT)
        assertFalse(s.power); assertEquals(WifiAcMode.HEAT, s.mode)
        assertFalse(ElectraCodec.caps(o).swing)
        assertEquals("STBY", ElectraCodec.applyState(o, s)["AC_MODE"]!!.jsonPrimitive.content)
        assertEquals("HEAT", ElectraCodec.applyState(o, s.copy(power = true))["AC_MODE"]!!.jsonPrimitive.content)
    }

    @Test fun electraDevicesAndTokens() {
        val r = obj("""{"status":0,"data":{"res":0,"devices":[{"id":101,"name":"Salon","mac":"AA","model":"X","deviceTypeName":"A/C"},{"id":102,"deviceTypeName":"Boiler","mac":"BB"}]}}""")
        val list = ElectraCodec.devices(r)
        assertEquals(1, list.size)
        assertEquals("101", list[0].id); assertEquals("Salon", list[0].name)
        assertEquals("tok", ElectraCodec.token(obj("""{"status":0,"data":{"res":0,"token":"tok"}}""")))
        assertEquals("s1", ElectraCodec.sid(obj("""{"status":0,"data":{"res":0,"sid":"s1"}}""")))
        assertFalse(ElectraCodec.isOk(obj("""{"status":0,"data":{"res":1,"res_desc":"bad"}}""")))
    }

    // ---------------------------------------------------------------- Sensibo

    private val pods = """{"status":"success","result":[{"id":"abc","room":{"name":"Bedroom"},
        "acState":{"on":true,"mode":"cool","targetTemperature":22,"temperatureUnit":"C","fanLevel":"medium","swing":"rangeFull"},
        "measurements":{"temperature":24.5,"humidity":50},
        "remoteCapabilities":{"modes":{"cool":{"temperatures":{"C":{"isNative":true,"values":[18,19,20,21,22,23,24,25,26,27,28,29,30]}},"fanLevels":["low","medium","high","auto"],"swing":["stopped","rangeFull"]},
          "fan":{"fanLevels":["low","high"],"swing":["stopped"]}}}}]}"""

    @Test fun sensiboPodsParsed() {
        val p = SensiboCodec.pods(obj(pods)).single()
        assertEquals("abc", p.id); assertEquals("Bedroom", p.name); assertEquals(24.5, p.roomC!!, 0.01)
        val s = SensiboCodec.state(p)
        assertEquals(WifiAcState(true, WifiAcMode.COOL, 22, WifiFan.MEDIUM, swing = true), s)
        val caps = SensiboCodec.caps(p, WifiAcMode.COOL)
        assertEquals(listOf(WifiAcMode.COOL, WifiAcMode.FAN), caps.modes)
        assertEquals(18..30, caps.tempRange)
        assertTrue(caps.swing)
        val fanCaps = SensiboCodec.caps(p, WifiAcMode.FAN)
        assertEquals(listOf(WifiFan.LOW, WifiFan.HIGH), fanCaps.fans)
        assertFalse(fanCaps.swing)
        assertTrue(WifiAcMode.FAN in fanCaps.tempIgnoredIn)
    }

    @Test fun sensiboBody() {
        val p = SensiboCodec.pods(obj(pods)).single()
        val body = obj(SensiboCodec.acStateBody(WifiAcState(true, WifiAcMode.COOL, 25, WifiFan.HIGH, swing = false), p))["acState"]!!.jsonObject
        assertEquals("cool", body["mode"]!!.jsonPrimitive.content)
        assertEquals("25", body["targetTemperature"]!!.jsonPrimitive.content)
        assertEquals("high", body["fanLevel"]!!.jsonPrimitive.content)
        assertEquals("stopped", body["swing"]!!.jsonPrimitive.content)
        // Fan mode has no temperatures and no swing positions: those keys are left out.
        val fan = obj(SensiboCodec.acStateBody(WifiAcState(true, WifiAcMode.FAN, 25, WifiFan.MEDIUM, swing = true), p))["acState"]!!.jsonObject
        assertNull(fan["targetTemperature"]); assertNull(fan["swing"])
        assertEquals("low", fan["fanLevel"]!!.jsonPrimitive.content) // no medium in fan mode: nearest available
        assertTrue(SensiboCodec.podsUrl("k y").endsWith("apiKey=k+y"))
    }

    // ---------------------------------------------------------------- discovery

    @Test fun ssdpAndroidTvIsControllable() {
        val r = Ssdp.parse("HTTP/1.1 200 OK\r\nST: urn:dial-multiscreen-org:service:dial:1\r\nSERVER: Linux/4.9 UPnP/1.0 Android TV/11\r\n\r\n")!!
        assertEquals(WifiDeviceKind.TV_ANDROID to WifiProtocol.ANDROID_TV, Ssdp.classify(r, null))
        assertTrue(WifiProtocol.ANDROID_TV.controllable)
        assertTrue(WifiProtocol.ELECTRA.cloud && WifiProtocol.ELECTRA.isAc)
    }
}
