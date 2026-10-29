package com.universalir.ir

import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.ac.AcMode
import com.universalir.ir.protocol.ac.AcState
import com.universalir.ir.protocol.ac.CoolixProtocol
import com.universalir.ir.protocol.ac.Electra34Protocol
import com.universalir.ir.protocol.ac.ElectraAcProtocol
import com.universalir.ir.protocol.ac.ElectraAcVariant
import com.universalir.ir.protocol.ac.FanSpeed
import com.universalir.ir.protocol.ac.GreeProtocol
import com.universalir.ir.protocol.ac.SwingMode
import com.universalir.ir.protocol.ac.TadiranProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compares encoder output with real remote captures (SmartIR, electra-central-ac-ir-codes) and
 * with IRremoteESP8266's published timing vectors.
 */
class AcProtocolCaptureTest {

    private fun parseKey(key: String): Triple<AcMode, FanSpeed, Int>? {
        val parts = key.split('/')
        if (parts.size != 3) return null
        val mode = when (parts[0]) { "cool" -> AcMode.COOL; "heat" -> AcMode.HEAT; "fan_only", "fan" -> AcMode.FAN_ONLY; "dry" -> AcMode.DRY; "auto" -> AcMode.AUTO; else -> return null }
        val fan = when (parts[1]) { "low" -> FanSpeed.LOW; "mid", "med", "medium" -> FanSpeed.MEDIUM; "high" -> FanSpeed.HIGH; "auto" -> FanSpeed.AUTO; else -> return null }
        return Triple(mode, fan, parts[2].toDouble().toInt())
    }

    // ---------------------------------------------------------------- Tadiran (IRTadiran)

    @Test
    fun tadiranEncoderReproducesCapturedFrames() {
        val p = TadiranProtocol()
        for (file in listOf("1340.json", "1560.json")) {
            var matched = 0
            TestSupport.smartIrCommands(file).forEach { (key, b64) ->
                val t = TestSupport.broadlink(b64).timings()
                val captured = TestSupport.lsbBytes(TestSupport.pulseWidthBits(t, 2, 64))
                val state = if (key == "off") {
                    // Off frames carry the remote's last state with byte5 = 0xC0.
                    AcState(false, AcMode.COOL, captured[2] / 2, fanOf(captured[1] shr 4), if (captured[6] != 0) SwingMode.AUTO else SwingMode.OFF)
                        .copy(mode = modeOf(captured[1] and 0xF))
                } else {
                    val (mode, fan, _) = parseKey(key) ?: return@forEach
                    AcState(true, mode, captured[2] / 2, fan, if (captured[6] != 0) SwingMode.AUTO else SwingMode.OFF)
                }
                assertEquals("$file $key", TestSupport.hex(captured), TestSupport.hex(p.buildBytes(state)))
                // Waveform shape: compare our timings with the capture (Broadlink adds a trailing gap).
                TestSupport.assertTimingsClose(t, p.toPattern(p.buildBytes(state)).timings(), compareCount = 263)
                matched++
            }
            assertTrue("$file matched $matched", matched >= 130)
        }
    }

    @Test
    fun tadiranOffChecksumMatchesIrTadiranHardcodedFrame() {
        // IRTadiran.cpp send(power=false): 01 14 30 00 00 C0 00 15
        val code = intArrayOf(0x01, 0x14, 0x30, 0x00, 0x00, 0xC0, 0x00, 0)
        assertEquals(0x15, TadiranProtocol().checksum(code))
    }

    private fun fanOf(n: Int) = when (n) { 1 -> FanSpeed.LOW; 2 -> FanSpeed.MEDIUM; 3 -> FanSpeed.HIGH; else -> FanSpeed.AUTO }
    private fun modeOf(n: Int) = when (n) { 2 -> AcMode.HEAT; 3 -> AcMode.FAN_ONLY; 4 -> AcMode.DRY; else -> AcMode.COOL }

    // ---------------------------------------------------------------- Gree

    @Test
    fun greeMatchesIrRemoteEsp8266SendVector() {
        val p = GreeProtocol()
        val pattern = p.toPattern(intArrayOf(0x12, 0x34, 0x56, 0x78, 0x90, 0xAB, 0xCD, 0xEF))
        val expected = "m9000s4500" +
            "m620s540m620s1600m620s540m620s540m620s1600m620s540m620s540m620s540" +
            "m620s540m620s540m620s1600m620s540m620s1600m620s1600m620s540m620s540" +
            "m620s540m620s1600m620s1600m620s540m620s1600m620s540m620s1600m620s540" +
            "m620s540m620s540m620s540m620s1600m620s1600m620s1600m620s1600m620s540" +
            "m620s540m620s1600m620s540" +
            "m620s19980" +
            "m620s540m620s540m620s540m620s540m620s1600m620s540m620s540m620s1600" +
            "m620s1600m620s1600m620s540m620s1600m620s540m620s1600m620s540m620s1600" +
            "m620s1600m620s540m620s1600m620s1600m620s540m620s540m620s1600m620s1600" +
            "m620s1600m620s1600m620s1600m620s1600m620s540m620s1600m620s1600m620s1600" +
            "m620"
        assertEquals(expected, mS(pattern.timings()))
    }

    @Test
    fun greeChecksumValidOnTadiranCapturesAndEncoderReproducesThem() {
        val p = GreeProtocol()
        var checked = 0
        var reproduced = 0
        TestSupport.smartIrCommands("1342.json").forEach { (key, b64) ->
            val t = TestSupport.broadlink(b64).timings()
            if (t[0] < 8000) return@forEach
            val first = TestSupport.pulseDistanceBits(t, 2, 32)
            val second = TestSupport.pulseDistanceBits(t, 2 + 35 * 2 + 2, 32)
            val bytes = TestSupport.lsbBytes(first + second)
            assertEquals("$key ${TestSupport.hex(bytes)}", p.checksum(bytes), bytes[7] shr 4)
            checked++
            // Rebuild the state from the captured fields (SmartIR labels contain mistakes) and
            // require a byte-exact reproduction, which exercises layout and checksum together.
            val mode = when (bytes[0] and 7) { 0 -> AcMode.AUTO; 1 -> AcMode.COOL; 2 -> AcMode.DRY; 3 -> AcMode.FAN_ONLY; else -> AcMode.HEAT }
            val fan = when ((bytes[0] shr 4) and 3) { 0 -> FanSpeed.AUTO; 1 -> FanSpeed.LOW; 2 -> FanSpeed.MEDIUM; else -> FanSpeed.HIGH }
            val state = AcState(
                power = bytes[0] and 0x08 != 0, mode = mode, temperatureCelsius = (bytes[1] and 0x0F) + 16, fanSpeed = fan,
                verticalSwing = if (bytes[0] and 0x40 != 0) SwingMode.AUTO else SwingMode.OFF,
                light = bytes[2] and 0x20 != 0,
            )
            val ours = p.buildBytes(state)
            if (TestSupport.hex(ours) == TestSupport.hex(bytes)) reproduced++ else println("Gree $key captured ${TestSupport.hex(bytes)} ours ${TestSupport.hex(ours)}")
        }
        assertTrue("checked $checked", checked > 50)
        // Every fully-modelled state must be reproduced exactly.
        assertEquals("reproduced", checked, reproduced)
    }

    private fun mS(t: IntArray) = t.mapIndexed { i, v -> (if (i % 2 == 0) "m" else "s") + v }.joinToString("")

    // ---------------------------------------------------------------- Electra 104-bit

    @Test
    fun electraAcMatchesIrRemoteEsp8266SendVector() {
        val p = ElectraAcProtocol()
        val data = intArrayOf(0xC3, 0x87, 0xF6, 0x28, 0x60, 0x00, 0x20, 0x00, 0x00, 0x20, 0x00, 0x05, 0x0D)
        val expected = "m9166s4470" +
            "m646s1647m646s1647m646s547m646s547m646s547m646s547m646s1647m646s1647" +
            "m646s1647m646s1647m646s1647m646s547m646s547m646s547m646s547m646s1647" +
            "m646s547m646s1647m646s1647m646s547m646s1647m646s1647m646s1647m646s1647" +
            "m646s547m646s547m646s547m646s1647m646s547m646s1647m646s547m646s547" +
            "m646s547m646s547m646s547m646s547m646s547m646s1647m646s1647m646s547" +
            "m646s547m646s547m646s547m646s547m646s547m646s547m646s547m646s547" +
            "m646s547m646s547m646s547m646s547m646s547m646s1647m646s547m646s547" +
            "m646s547m646s547m646s547m646s547m646s547m646s547m646s547m646s547" +
            "m646s547m646s547m646s547m646s547m646s547m646s547m646s547m646s547" +
            "m646s547m646s547m646s547m646s547m646s547m646s1647m646s547m646s547" +
            "m646s547m646s547m646s547m646s547m646s547m646s547m646s547m646s547" +
            "m646s1647m646s547m646s1647m646s547m646s547m646s547m646s547m646s547" +
            "m646s1647m646s547m646s1647m646s1647m646s547m646s547m646s547m646s547" +
            "m646"
        assertEquals(expected, mS(p.toPattern(data).timings()))
    }

    @Test
    fun electraAcReproducesCapturedIssue778States() {
        // Real captures quoted in IRremoteESP8266 test/ir_Electra_test.cpp (issue #778).
        val p32 = ElectraAcProtocol(ElectraAcVariant(0x00, 0x00, 0x40))
        assertEquals("C3 C7 E0 00 A0 00 20 00 00 20 00 40 8A",
            TestSupport.hex(p32.buildBytes(AcState(true, AcMode.COOL, 32, FanSpeed.AUTO))))
        val p16 = ElectraAcProtocol(ElectraAcVariant(0x00, 0x00, 0x41))
        assertEquals("C3 47 E0 00 A0 00 20 00 00 20 00 41 0B",
            TestSupport.hex(p16.buildBytes(AcState(true, AcMode.COOL, 16, FanSpeed.AUTO))))
        assertEquals("C3 47 E0 00 60 00 20 00 00 20 00 41 CB",
            TestSupport.hex(p16.buildBytes(AcState(true, AcMode.COOL, 16, FanSpeed.LOW))))
    }

    @Test
    fun electraClassicCapturesHaveValidChecksum() {
        val p = ElectraAcProtocol()
        var n = 0
        val bad = mutableListOf<String>()
        TestSupport.smartIrCommands("1942.json").values.forEach { b64 ->
            // A handful of published packets are corrupt; the importer rejects them too.
            val t = runCatching { TestSupport.broadlink(b64).timings() }.getOrNull() ?: return@forEach
            if (t[0] < 8000 || t.size < 210) return@forEach
            val bytes = TestSupport.lsbBytes(TestSupport.pulseDistanceBits(t, 2, 104))
            assertEquals(0xC3, bytes[0])
            if (p.checksum(bytes) == bytes[12]) n++ else bad += TestSupport.hex(bytes)
        }
        println("Electra 1942: $n valid, ${bad.size} invalid: $bad")
        assertTrue(n > 50)
        assertTrue("invalid frames: $bad", bad.size * 20 <= n)
    }

    // ---------------------------------------------------------------- Electra 34-bit Manchester

    /** Manchester-decodes a capture into its first 34-bit core (bit 0 = mark,space). */
    private fun manchesterCore(t: IntArray, half: Double = 1000.0): String {
        val samples = ArrayList<Int>()
        t.forEachIndexed { i, v -> if (v < 6000) repeat(maxOf(1, Math.round(v / half).toInt())) { samples += if (i % 2 == 0) 1 else 0 } }
        // Skip the 3+3 half-bit header.
        val bits = StringBuilder()
        var i = 6
        while (i + 1 < samples.size && bits.length < 34) {
            bits.append(if (samples[i] == 1 && samples[i + 1] == 0) '0' else if (samples[i] == 0 && samples[i + 1] == 1) '1' else '?')
            i += 2
        }
        return bits.toString()
    }

    @Test
    fun electra34MatchesCentralAcCaptures() {
        val p = Electra34Protocol()
        val cases = mapOf(
            "anchor_20" to AcState(true, AcMode.COOL, 20, FanSpeed.HIGH),
            "anchor_24" to AcState(true, AcMode.COOL, 24, FanSpeed.HIGH),
            "fan_auto_22" to AcState(true, AcMode.COOL, 22, FanSpeed.AUTO),
            "fan_high_22" to AcState(true, AcMode.COOL, 22, FanSpeed.HIGH),
            "fan_low_22" to AcState(true, AcMode.COOL, 22, FanSpeed.LOW),
            "fan_med_22" to AcState(true, AcMode.COOL, 22, FanSpeed.MEDIUM),
            "heat_low_24" to AcState(true, AcMode.HEAT, 24, FanSpeed.LOW),
        )
        cases.forEach { (file, state) ->
            val t = TestSupport.broadlink(TestSupport.resource("electra_central/$file.b64")).timings()
            val captured = manchesterCore(t, 965.0)
            assertEquals(file, captured, p.bitString(p.coreBits(state, toggle = false)))
        }
        val toggle = manchesterCore(TestSupport.broadlink(TestSupport.resource("electra_central/power_toggle.b64")).timings(), 965.0)
        assertEquals('1', toggle[0])
    }

    @Test
    fun electra34TogglesPowerFromAssumedState() {
        val p = Electra34Protocol()
        val on = p.encodeCompleteState(AcState(true, AcMode.COOL, 22), previous = AcState(false)) as EncodeResult.Success
        assertEquals(2, on.transmission.messages.size)
        val change = p.encodeCompleteState(AcState(true, AcMode.COOL, 23), previous = AcState(true)) as EncodeResult.Success
        assertEquals(1, change.transmission.messages.size)
        assertTrue(p.encodeCompleteState(AcState(false), previous = AcState(false)) is EncodeResult.Failure)
        val pattern = on.transmission.messages[1].pattern
        assertTrue(pattern.totalDurationUs < 2_000_000)
    }

    // ---------------------------------------------------------------- Coolix

    /** SmartIR 1944 stores the 17 °C and 18 °C heat/auto codes under each other's keys. */
    private val knownLabelErrors = setOf("1944.json heat/auto/17", "1944.json heat/auto/18")

    @Test
    fun coolixCapturesDecodeToEncoderCodes() {
        val p = CoolixProtocol()
        for (file in listOf("1620.json", "1944.json")) {
            var matched = 0
            var total = 0
            TestSupport.smartIrCommands(file).forEach { (key, b64) ->
                val t = TestSupport.broadlink(b64).timings()
                if (t[0] < 3500) return@forEach
                val bits = TestSupport.pulseDistanceBits(t, 2, 48)
                val b = TestSupport.msbBytes(bits)
                for (i in 0 until 3) assertEquals("$file $key inverse", b[i * 2] xor 0xFF, b[i * 2 + 1])
                val code = (b[0] shl 16) or (b[2] shl 8) or b[4]
                if (key != "off" && parseKey(key) == null) return@forEach
                total++
                val expected = if (key == "off") p.buildCode(AcState(false)) else {
                    val (mode, labelledFan, temp) = parseKey(key) ?: return@forEach
                    // SmartIR 1620 labels the AUTO fan code (0b101) as "low"; see docs/RESEARCH.md.
                    val fan = if (file == "1620.json" && labelledFan == FanSpeed.LOW) FanSpeed.AUTO else labelledFan
                    p.buildCode(AcState(true, mode, temp, fan))
                }
                if ("$file $key" in knownLabelErrors) { total--; return@forEach }
                if (expected == code) matched++ else println("Coolix $file $key captured %06X ours %06X".format(code, expected))
            }
            assertEquals("$file matched", total, matched)
        }
    }
}
