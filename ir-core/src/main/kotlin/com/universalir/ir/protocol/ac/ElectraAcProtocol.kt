// Derived from IRremoteESP8266 (ir_Electra.cpp / ir_Electra.h),
// Copyright 2018-2021 David Conran and contributors, LGPL-2.1. See NOTICE.md.
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
 * Electra 104-bit (13 byte) protocol, first byte 0xC3, checksum = sum of bytes 0..11.
 * IRremoteESP8266 marks this "Alpha / needs testing"; the carrier frequency is its guess.
 *
 * Captured remotes differ in a few bytes that IRremoteESP8266 does not model (byte 2 low bits,
 * byte 3, byte 11). [ElectraAcVariant] keeps those as fixed per-remote constants so a captured
 * remote can be reproduced exactly.
 */
class ElectraAcProtocol(private val variant: ElectraAcVariant = ElectraAcVariant.DEFAULT) : AcProtocol {

    override val info = ProtocolInfo(
        id = ID,
        displayName = "Electra / AUX 104-bit (0xC3)",
        deviceCategories = setOf(DeviceCategory.AIR_CONDITIONER),
        carrierHz = CARRIER_HZ,
        frameDescription = "Header $HDR_MARK/$HDR_SPACE us, bit mark $BIT_MARK, one $ONE_SPACE, zero $ZERO_SPACE, " +
            "LSB first, 13 bytes, byte 12 = sum(bytes 0..11).",
        completeState = true,
        sources = listOf(
            SourceRef("IRremoteESP8266 ir_Electra.cpp", "https://github.com/crankyoldgit/IRremoteESP8266/blob/master/src/ir_Electra.cpp", "LGPL-2.1", "Status: Alpha"),
            SourceRef("SmartIR climate 1942 (Electra Classic)", "https://github.com/zephyrx47/SmartIR", "MIT", "Captured frames decode with valid checksum"),
            SourceRef("Flipper-IRDB ACs/Electra/electra_smart.ir", "https://github.com/Lucaslhm/Flipper-IRDB", "CC0-1.0 (post-2319685 commits)", "Electra Smart remote, 0xC3 frames"),
        ),
        verification = VerificationLevel.WAVEFORM_VALIDATED,
        evidence = listOf(
            "Bit-exact against IRremoteESP8266 TestSendElectraAC timing vector.",
            "Encoder reproduces captured state bytes from IRremoteESP8266 issue #778 (real remote).",
        ),
        warnings = listOf(
            "Upstream status is Alpha: not yet confirmed against hardware by IRremoteESP8266.",
            "38 kHz carrier is upstream's guess.",
            "Light is a toggle command and is not exposed.",
        ),
    )

    override val capabilities = AcCapabilities(
        temperatureRange = 16..32,
        modes = setOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.HEAT, AcMode.FAN_ONLY),
        fanSpeeds = setOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH),
        verticalSwing = setOf(SwingMode.OFF, SwingMode.AUTO),
        horizontalSwing = setOf(SwingMode.OFF, SwingMode.AUTO),
        features = setOf(AcFeature.TURBO, AcFeature.QUIET, AcFeature.IFEEL),
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
        val s = IntArray(13)
        s[0] = 0xC3
        val swingV = if (state.verticalSwing == SwingMode.AUTO) 0b000 else 0b111
        val swingH = if (state.horizontalSwing == SwingMode.AUTO) 0b000 else 0b111
        val temp = state.temperatureCelsius.coerceIn(16, 32) - 8
        s[1] = swingV or (temp shl 3)
        s[2] = (variant.byte2Low and 0x1F) or (swingH shl 5)
        s[3] = variant.byte3 and 0xBF
        s[4] = when (state.fanSpeed) {
            FanSpeed.AUTO -> 0b101; FanSpeed.LOW -> 0b011; FanSpeed.MEDIUM -> 0b010; FanSpeed.HIGH -> 0b001
        } shl 5
        s[5] = (if (state.turbo == true) 0x40 else 0) or (if (state.quiet == true) 0x80 else 0)
        val mode = when (state.mode) {
            AcMode.AUTO -> 0b000; AcMode.COOL -> 0b001; AcMode.DRY -> 0b010; AcMode.HEAT -> 0b100; AcMode.FAN_ONLY -> 0b110
        }
        val iFeel = state.iFeel == true
        s[6] = (mode shl 5) or (if (iFeel) 0x08 else 0)
        if (iFeel) {
            val sensor = state.modelSpecificFields[AcState.FIELD_IFEEL_SENSOR_C]!!.toInt().coerceIn(0, 50)
            s[7] = sensor + SENSOR_TEMP_DELTA
        }
        s[9] = if (state.power) 0x20 else 0x00
        s[11] = variant.byte11 and 0xFF
        s[12] = checksum(s)
        return s
    }

    fun checksum(s: IntArray): Int {
        var sum = 0
        for (i in 0 until 12) sum += s[i]
        return sum and 0xFF
    }

    fun toPattern(bytes: IntArray): IrPattern {
        val b = TimingBuilder()
        b.beginFrame().mark(HDR_MARK).space(HDR_SPACE)
        b.pulseDistanceBits(Bits.lsbFirst(bytes), BIT_MARK, ONE_SPACE, ZERO_SPACE)
        b.mark(BIT_MARK)
        return b.build(CARRIER_HZ)
    }

    companion object {
        const val ID = "electra_ac104"
        const val CARRIER_HZ = 38_000
        const val HDR_MARK = 9166
        const val HDR_SPACE = 4470
        const val BIT_MARK = 646
        const val ONE_SPACE = 1647
        const val ZERO_SPACE = 547
        const val SENSOR_TEMP_DELTA = 0x4A
    }
}

/** Remote-specific constant bytes not modelled by IRremoteESP8266. */
data class ElectraAcVariant(val byte2Low: Int, val byte3: Int, val byte11: Int) {
    companion object {
        /** IRremoteESP8266 stateReset(): all zero except LightToggle = 0x08. */
        val DEFAULT = ElectraAcVariant(byte2Low = 0x00, byte3 = 0x00, byte11 = 0x08)
    }
}
