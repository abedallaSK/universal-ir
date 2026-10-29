package com.universalir.ir

import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.ac.AcMode
import com.universalir.ir.protocol.ac.AcState
import com.universalir.ir.protocol.ac.FanSpeed
import com.universalir.ir.protocol.ac.Kelon168Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference vectors are the self-test table in Aikidyst/kelon168-ir-homeassistant `kelon168.py`. */
class Kelon168ProtocolTest {
    private val p = Kelon168Protocol()

    private fun v(vararg b: Int) = b

    private val vectors: List<Pair<AcState, IntArray>> = listOf(
        AcState(true, AcMode.COOL, 22, FanSpeed.AUTO) to v(0x83, 0x06, 0x00, 0x62, 0x00, 0x00, 0x8C, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0xEB, 0x00, 0x02, 0x00, 0x00, 0x08, 0x00, 0x0A),
        AcState(true, AcMode.COOL, 22, FanSpeed.LOW) to v(0x83, 0x06, 0x03, 0x62, 0x00, 0x00, 0x8C, 0x32, 0x00, 0x00, 0x00, 0x00, 0x00, 0xDF, 0x00, 0x11, 0x00, 0x00, 0x08, 0x00, 0x19),
        AcState(true, AcMode.COOL, 22, FanSpeed.MEDIUM) to v(0x83, 0x06, 0x02, 0x62, 0x00, 0x00, 0x8C, 0x2E, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC2, 0x00, 0x11, 0x00, 0x00, 0x08, 0x00, 0x19),
        AcState(true, AcMode.COOL, 22, FanSpeed.HIGH) to v(0x83, 0x06, 0x01, 0x62, 0x00, 0x00, 0x8C, 0x2E, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC1, 0x00, 0x11, 0x00, 0x00, 0x08, 0x00, 0x19),
        AcState(true, AcMode.COOL, 18, FanSpeed.AUTO) to v(0x83, 0x06, 0x00, 0x22, 0x00, 0x00, 0x8C, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0xAB, 0x00, 0x02, 0x00, 0x00, 0x08, 0x00, 0x0A),
        AcState(true, AcMode.HEAT, 22, FanSpeed.AUTO) to v(0x83, 0x06, 0x00, 0x60, 0x00, 0x00, 0x8C, 0x25, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC9, 0x00, 0x02, 0x00, 0x00, 0x08, 0x00, 0x0A),
        AcState(true, AcMode.DRY, 22, FanSpeed.AUTO) to v(0x83, 0x06, 0x00, 0x73, 0x00, 0x00, 0x8C, 0x28, 0x00, 0x00, 0x00, 0x00, 0x00, 0xD7, 0x00, 0x06, 0x00, 0x00, 0x08, 0x00, 0x0E),
        AcState(true, AcMode.FAN_ONLY, 22, FanSpeed.AUTO) to v(0x83, 0x06, 0x01, 0x74, 0x00, 0x00, 0x8C, 0x2A, 0x00, 0x00, 0x00, 0x00, 0x00, 0xD3, 0x00, 0x06, 0x00, 0x00, 0x08, 0x00, 0x0E),
        AcState(true, AcMode.COOL, 22, FanSpeed.AUTO, iFeel = true, modelSpecificFields = mapOf(AcState.FIELD_IFEEL_SENSOR_C to "26")) to
            v(0x83, 0x06, 0x00, 0x62, 0x00, 0x00, 0x8D, 0x05, 0x00, 0x00, 0x00, 0x80, 0x1A, 0x70, 0x00, 0x0D, 0x00, 0x00, 0x08, 0x00, 0x05),
    )

    @Test
    fun stateBytesMatchReferenceVectors() {
        vectors.forEach { (state, expected) ->
            assertEquals(state.toString(), TestSupport.hex(expected), TestSupport.hex(p.buildState(state, turbo = false)))
        }
    }

    @Test
    fun turboFrameMatchesReference() {
        val expected = v(0x83, 0x06, 0x01, 0x02, 0x00, 0x90, 0x8C, 0x38, 0x00, 0x00, 0x00, 0x00, 0x00, 0x27, 0x00, 0x04, 0x00, 0x00, 0x08, 0x00, 0x0C)
        assertEquals(TestSupport.hex(expected), TestSupport.hex(p.buildState(AcState(true), turbo = true)))
    }

    @Test
    fun waveformHas343EntriesAndThreeFrames() {
        val pattern = p.toPattern(p.buildState(AcState(true, AcMode.COOL, 22), false))
        assertEquals(343, pattern.size)
        assertEquals(listOf(0, 100, 230), pattern.frameStarts)
        val t = pattern.timings()
        assertEquals(8308, t[0]); assertEquals(4527, t[1]); assertEquals(8308, t[99]); assertEquals(8308, t[229])
        assertTrue(pattern.totalDurationUs < 2_000_000)
    }

    @Test
    fun powerSequencesUseTurboForcing() {
        val on = p.encodeCompleteState(AcState(true, AcMode.COOL, 24), previous = null) as EncodeResult.Success
        assertEquals(listOf("turbo (forces on)", "state"), on.transmission.messages.map { it.label })
        val off = p.encodeCompleteState(AcState(false, AcMode.COOL, 24), previous = AcState(true)) as EncodeResult.Success
        assertEquals(listOf("turbo (forces on)", "power toggle"), off.transmission.messages.map { it.label })
        val change = p.encodeCompleteState(AcState(true, AcMode.COOL, 23), previous = AcState(true, AcMode.COOL, 24)) as EncodeResult.Success
        assertEquals(1, change.transmission.messages.size)
    }

    @Test
    fun powerToggleFrameFollowsSourceSignature() {
        val b = p.buildPowerToggle(AcState(true, AcMode.COOL, 22, FanSpeed.AUTO))
        assertEquals(0x04, b[2]); assertEquals(0x62, b[3]); assertEquals(0x95, b[6]); assertEquals(0x24, b[7])
        assertEquals(0x01, b[15]); assertEquals(0x09, b[20])
    }

    @Test
    fun rejectsUnsupportedState() {
        val r = p.encodeCompleteState(AcState(true, AcMode.AUTO, 22), null)
        assertTrue(r is EncodeResult.Failure)
        val hot = p.encodeCompleteState(AcState(true, AcMode.COOL, 31), null)
        assertTrue(hot is EncodeResult.Failure)
    }

    /** SmartIR 1621 (Tornado Super Legend 40): a captured frame must decode as valid KELON168. */
    @Test
    fun tornadoCaptureDecodesAsKelon168WithValidChecksums() {
        val cmds = TestSupport.smartIrCommands("1621.json")
        var checked = 0
        cmds.filterKeys { it != "off" }.values.forEach { b64 ->
            val t = TestSupport.broadlink(b64).timings()
            // A few SmartIR captures are truncated or start mid-signal; only score clean frames.
            if (t.size < 343 || t[0] < 8000) return@forEach
            val f1 = TestSupport.pulseDistanceBits(t, 2, 48)
            val f2 = TestSupport.pulseDistanceBits(t, 100, 64)
            val f3 = TestSupport.pulseDistanceBits(t, 230, 56)
            val bytes = TestSupport.lsbBytes(f1 + f2 + f3)
            assertEquals(0x83, bytes[0]); assertEquals(0x06, bytes[1])
            var x = 0
            for (i in 0 until 13) x = x xor bytes[i]
            assertEquals("byte13 in ${TestSupport.hex(bytes)}", x xor 0x85, bytes[13])
            assertEquals(bytes[15] xor 0x08, bytes[20])
            checked++
        }
        assertTrue("expected KELON168 frames in 1621, checked $checked", checked >= 100)
    }
}
