// Derived from IRremoteESP8266 (ir_Coolix.cpp / ir_Coolix.h),
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
 * Coolix 24-bit protocol (Midea-made units), each byte followed by its inverse, MSB first,
 * sent twice. Settings frames are complete state; swing is a separate toggle command and off is
 * a fixed code.
 */
class CoolixProtocol : AcProtocol {

    override val info = ProtocolInfo(
        id = ID,
        displayName = "Coolix 24-bit (Midea OEM)",
        deviceCategories = setOf(DeviceCategory.AIR_CONDITIONER),
        carrierHz = CARRIER_HZ,
        frameDescription = "Header $HDR_MARK/$HDR_SPACE us, bit mark $BIT_MARK, one $ONE_SPACE, zero $ZERO_SPACE, " +
            "MSB first, each byte followed by its inverse, footer mark + $MIN_GAP us, sent twice.",
        completeState = true,
        sources = listOf(
            SourceRef("IRremoteESP8266 ir_Coolix.cpp", "https://github.com/crankyoldgit/IRremoteESP8266/blob/master/src/ir_Coolix.cpp", "LGPL-2.1"),
            SourceRef("SmartIR climate 1620 / 1941 / 1943 / 1944", "https://github.com/zephyrx47/SmartIR", "MIT", "Tornado Super Inverter and Electra Platinum Plus Inverter captures start with 0xB2"),
        ),
        verification = VerificationLevel.WAVEFORM_VALIDATED,
        evidence = listOf("Encoder reproduces captured 24-bit codes from SmartIR Tornado 1620 / Electra 1944 (unit tests)."),
        warnings = listOf("Swing, turbo, LED and sleep are toggle commands in this protocol, not state."),
    )

    override val capabilities = AcCapabilities(
        temperatureRange = 17..30,
        modes = setOf(AcMode.AUTO, AcMode.COOL, AcMode.DRY, AcMode.HEAT, AcMode.FAN_ONLY),
        fanSpeeds = setOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH),
        fanSpeedsByMode = mapOf(AcMode.AUTO to setOf(FanSpeed.AUTO), AcMode.DRY to setOf(FanSpeed.AUTO)),
        temperatureIgnoredIn = setOf(AcMode.FAN_ONLY),
        powerControl = PowerControl.ABSOLUTE,
        extraCommands = listOf(AcExtraCommand(EXTRA_SWING, "Swing (toggle)", "Toggles the vane swing.")),
    )

    override fun encodeCompleteState(state: AcState, previous: AcState?): EncodeResult {
        val issues = validateState(state)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues)
        val code = buildCode(state)
        return EncodeResult.Success(IrTransmission.single(toPattern(code), "state"), listOf("%06X".format(code)), describeState(state))
    }

    override fun encodeExtra(commandId: String, state: AcState): EncodeResult =
        if (commandId == EXTRA_SWING) EncodeResult.Success(IrTransmission.single(toPattern(SWING_CODE), "swing"), listOf("%06X".format(SWING_CODE)), "Swing toggle")
        else super.encodeExtra(commandId, state)

    override fun describeState(state: AcState) =
        if (!state.power) "OFF" else "${state.mode} ${state.temperatureCelsius}°C fan ${state.fanSpeed}"

    fun buildCode(state: AcState): Int {
        if (!state.power) return OFF_CODE
        val fanAuto0 = state.mode == AcMode.AUTO || state.mode == AcMode.DRY
        val fan = when (state.fanSpeed) {
            FanSpeed.AUTO -> if (fanAuto0) 0b000 else 0b101
            FanSpeed.LOW -> 0b100
            FanSpeed.MEDIUM -> 0b010
            FanSpeed.HIGH -> 0b001
        }
        val (mode, tempCode) = when (state.mode) {
            AcMode.COOL -> 0b00 to tempCode(state.temperatureCelsius)
            AcMode.DRY -> 0b01 to tempCode(state.temperatureCelsius)
            AcMode.AUTO -> 0b10 to tempCode(state.temperatureCelsius)
            AcMode.HEAT -> 0b11 to tempCode(state.temperatureCelsius)
            AcMode.FAN_ONLY -> 0b01 to FAN_TEMP_CODE
        }
        val byte0 = (tempCode shl 4) or (mode shl 2)
        val byte1 = (fan shl 5) or SENSOR_IGNORE
        return (0xB2 shl 16) or (byte1 shl 8) or byte0
    }

    private fun tempCode(c: Int) = TEMP_MAP[c.coerceIn(17, 30) - 17]

    fun toPattern(code: Int): IrPattern {
        val b = TimingBuilder()
        repeat(2) {
            b.beginFrame().mark(HDR_MARK).space(HDR_SPACE)
            for (shift in intArrayOf(16, 8, 0)) {
                val byte = (code shr shift) and 0xFF
                b.pulseDistanceBits(Bits.msbFirst(byte.toLong(), 8), BIT_MARK, ONE_SPACE, ZERO_SPACE)
                b.pulseDistanceBits(Bits.msbFirst((byte xor 0xFF).toLong(), 8), BIT_MARK, ONE_SPACE, ZERO_SPACE)
            }
            b.mark(BIT_MARK).space(MIN_GAP)
        }
        return b.build(CARRIER_HZ)
    }

    companion object {
        const val ID = "coolix24"
        const val EXTRA_SWING = "swing_toggle"
        const val CARRIER_HZ = 38_000
        const val HDR_MARK = 4692
        const val HDR_SPACE = 4416
        const val BIT_MARK = 552
        const val ONE_SPACE = 1656
        const val ZERO_SPACE = 552
        const val MIN_GAP = 5244
        const val OFF_CODE = 0xB27BE0
        const val SWING_CODE = 0xB26BE0
        const val SENSOR_IGNORE = 0b11111
        const val FAN_TEMP_CODE = 0b1110
        val TEMP_MAP = intArrayOf(
            0b0000, 0b0001, 0b0011, 0b0010, 0b0110, 0b0111, 0b0101,
            0b0100, 0b1100, 0b1101, 0b1001, 0b1000, 0b1010, 0b1011,
        )
    }
}
