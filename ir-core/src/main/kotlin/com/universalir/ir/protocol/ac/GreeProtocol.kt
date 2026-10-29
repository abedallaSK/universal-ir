// Derived from IRremoteESP8266 (ir_Gree.cpp / ir_Gree.h, ir_Kelvinator.cpp checksum),
// Copyright David Conran and contributors, LGPL-2.1. See NOTICE.md.
package com.universalir.ir.protocol.ac

import com.universalir.ir.core.Bits
import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.IrTransmission
import com.universalir.ir.core.TimingBuilder
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.ProtocolInfo
import com.universalir.ir.protocol.SourceRef
import com.universalir.ir.protocol.VerificationLevel

/**
 * Gree 64-bit protocol (YAW1F remote family), as implemented by IRremoteESP8266.
 *
 * arikfe/IRTadiran's `IRTadiranSakal` is the same protocol: its 9-byte buffer is the Gree state
 * with the 3-bit `010` block footer embedded after bit 32, and its 35-bit / ~20 ms gap split is
 * Gree's block structure. SmartIR's Tadiran "10i/15i/inv220a" (1342) and "Alpha" (1343) captures
 * also decode as Gree.
 */
class GreeProtocol : AcProtocol {

    override val info = ProtocolInfo(
        id = ID,
        displayName = "Gree 64-bit (YAW1F) – incl. Tadiran Sakal",
        deviceCategories = setOf(DeviceCategory.AIR_CONDITIONER),
        carrierHz = CARRIER_HZ,
        frameDescription = "Header $HDR_MARK/$HDR_SPACE us, bit mark $BIT_MARK, one $ONE_SPACE, zero $ZERO_SPACE, " +
            "LSB first. 4 bytes + footer 010 + $MSG_SPACE us gap + 4 bytes. Checksum: high nibble of byte 7.",
        completeState = true,
        sources = listOf(
            SourceRef("IRremoteESP8266 ir_Gree.cpp", "https://github.com/crankyoldgit/IRremoteESP8266/blob/master/src/ir_Gree.cpp", "LGPL-2.1"),
            SourceRef("arikfe/IRTadiran (IRTadiranSakal)", "https://github.com/arikfe/IRTadiran", "MIT", "Shows Tadiran 'Sakal' remotes use this frame"),
            SourceRef("SmartIR climate 1342/1343", "https://github.com/zephyrx47/SmartIR", "MIT", "Tadiran 10i/15i/inv220a and Alpha captures"),
        ),
        verification = VerificationLevel.WAVEFORM_VALIDATED,
        evidence = listOf(
            "Bit-exact against IRremoteESP8266 TestSendGreeChars timing vector.",
            "Checksum validates on captured SmartIR 1342 (Tadiran) frames; encoder reproduces captured bytes.",
        ),
        warnings = listOf(
            "Follows the captured Tadiran remote and keeps the YAW1F ModelA bit set in off frames (IRremoteESP8266 clears it).",
            "Swing horizontal, I Feel, timers and Fahrenheit are not exposed.",
        ),
    )

    override val capabilities = AcCapabilities(
        temperatureRange = 16..30,
        modes = setOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.FAN_ONLY, AcMode.HEAT),
        fanSpeeds = setOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH),
        fanSpeedsByMode = mapOf(AcMode.DRY to setOf(FanSpeed.LOW)),
        temperatureIgnoredIn = setOf(AcMode.AUTO),
        verticalSwing = setOf(SwingMode.OFF, SwingMode.AUTO),
        features = setOf(AcFeature.TURBO, AcFeature.LIGHT, AcFeature.SLEEP),
        powerControl = PowerControl.ABSOLUTE,
    )

    override fun encodeCompleteState(state: AcState, previous: AcState?): EncodeResult {
        val issues = validateState(state)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues)
        val bytes = buildBytes(state)
        return EncodeResult.Success(IrTransmission.single(toPattern(bytes), "state"), listOf(Bits.hex(bytes)), describeState(state))
    }

    override fun describeState(state: AcState) =
        if (!state.power) "OFF" else "${state.mode} ${state.temperatureCelsius}°C fan ${state.fanSpeed}"

    fun buildBytes(state: AcState): IntArray {
        // stateReset(): Temp=9 (25C), Light, unknown1=0b0101, unknown2=0b100.
        val s = intArrayOf(0x00, 0x09, 0x20, 0x50, 0x00, 0x20, 0x00, 0x00)
        val mode = when (state.mode) {
            AcMode.AUTO -> 0; AcMode.COOL -> 1; AcMode.DRY -> 2; AcMode.FAN_ONLY -> 3; AcMode.HEAT -> 4
        }
        val fan = when {
            state.mode == AcMode.DRY -> 1
            else -> when (state.fanSpeed) { FanSpeed.AUTO -> 0; FanSpeed.LOW -> 1; FanSpeed.MEDIUM -> 2; FanSpeed.HIGH -> 3 }
        }
        val temp = if (state.mode == AcMode.AUTO) 25 else state.temperatureCelsius.coerceIn(16, 30)
        val swingAuto = state.verticalSwing == SwingMode.AUTO
        s[0] = mode or (if (state.power) 0x08 else 0) or (fan shl 4) or
            (if (swingAuto) 0x40 else 0) or (if (state.sleep == true) 0x80 else 0)
        s[1] = (temp - 16) and 0x0F
        s[2] = (if (state.turbo == true) 0x10 else 0) or
            (if (state.light != false) 0x20 else 0) or
            0x40 // ModelA (YAW1F). IRremoteESP8266 clears it when off; the captured Tadiran remote keeps it.
        s[4] = if (swingAuto) 0x01 else 0x00
        s[7] = checksum(s) shl 4
        return s
    }

    fun checksum(s: IntArray): Int {
        var sum = 10
        for (i in 0 until 4) sum += s[i] and 0x0F
        for (i in 4 until 7) sum += s[i] shr 4
        return sum and 0x0F
    }

    fun toPattern(bytes: IntArray): IrPattern {
        val b = TimingBuilder()
        b.beginFrame().mark(HDR_MARK).space(HDR_SPACE)
        b.pulseDistanceBits(Bits.lsbFirst(bytes.copyOfRange(0, 4)), BIT_MARK, ONE_SPACE, ZERO_SPACE)
        b.pulseDistanceBits(Bits.lsbFirst(0b010L, 3), BIT_MARK, ONE_SPACE, ZERO_SPACE)
        b.mark(BIT_MARK).space(MSG_SPACE)
        b.beginFrame().pulseDistanceBits(Bits.lsbFirst(bytes.copyOfRange(4, 8)), BIT_MARK, ONE_SPACE, ZERO_SPACE)
        b.mark(BIT_MARK)
        return b.build(CARRIER_HZ)
    }

    companion object {
        const val ID = "gree64"
        const val CARRIER_HZ = 38_000
        const val HDR_MARK = 9000
        const val HDR_SPACE = 4500
        const val BIT_MARK = 620
        const val ONE_SPACE = 1600
        const val ZERO_SPACE = 540
        const val MSG_SPACE = 19980
    }
}
