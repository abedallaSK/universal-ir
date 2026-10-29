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
 * Tadiran 64-bit protocol from arikfe/IRTadiran (MIT, `IRTadiran.cpp`).
 *
 * 8 bytes, LSB first, sent twice. Unusual pulse-width coding: a `1` is a long mark and short
 * space, a `0` a short mark and long space.
 *
 * The checksum formula is IRTadiran's empirical one. Validated against SmartIR captures 1340,
 * 1341 and 1560: it matches every "on" frame. "Off" frames (byte5 = 0xC0) use the same formula
 * minus 0x87; that offset reproduces IRTadiran's hard-coded off frame and both captured off frames.
 */
class TadiranProtocol : AcProtocol {

    override val info = ProtocolInfo(
        id = ID,
        displayName = "Tadiran 64-bit (IRTadiran)",
        deviceCategories = setOf(DeviceCategory.AIR_CONDITIONER),
        carrierHz = CARRIER_HZ,
        frameDescription = "Header $HDR_MARK/$HDR_SPACE us; bit 1 = $LONG/$SHORT us, bit 0 = $SHORT/$LONG us " +
            "(mark/space), LSB first, 8 bytes, sent twice with a $LONG us mark + $REPEAT_GAP us gap.",
        completeState = true,
        sources = listOf(
            SourceRef("arikfe/IRTadiran", "https://github.com/arikfe/IRTadiran", "MIT", "IRTadiran.cpp byte layout, timings, checksum"),
            SourceRef("SmartIR climate 1340/1341/1560", "https://github.com/zephyrx47/SmartIR", "MIT", "Captured Tadiran remotes used as reference vectors"),
        ),
        verification = VerificationLevel.WAVEFORM_VALIDATED,
        evidence = listOf(
            "Checksum matches 135/136 SmartIR 1340 frames, 89/91 1341 frames, 135/136 1560 frames; the outliers are off frames (handled) and one mislabelled capture.",
            "Encoder output matches captured SmartIR bytes for cool/heat/fan states (unit tests).",
        ),
        warnings = listOf(
            "DRY mode and AUTO fan exist in IRTadiran's comments but no capture confirms them, so they are not offered.",
            "Some Tadiran models (e.g. 10i/15i/inv220a, Alpha) use the Gree protocol instead.",
        ),
    )

    override val capabilities = AcCapabilities(
        temperatureRange = 16..30,
        modes = setOf(AcMode.COOL, AcMode.HEAT, AcMode.FAN_ONLY),
        fanSpeeds = setOf(FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH),
        verticalSwing = setOf(SwingMode.OFF, SwingMode.AUTO),
        powerControl = PowerControl.ABSOLUTE,
    )

    override fun safeTestState() = AcState(power = true, mode = AcMode.COOL, temperatureCelsius = 24, fanSpeed = FanSpeed.LOW)

    override fun encodeCompleteState(state: AcState, previous: AcState?): EncodeResult {
        val issues = validateState(state)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues)
        val bytes = buildBytes(state)
        return EncodeResult.Success(IrTransmission.single(toPattern(bytes), "state"), listOf(Bits.hex(bytes)), describeState(state))
    }

    override fun describeState(state: AcState) =
        if (!state.power) "OFF" else "${state.mode} ${state.temperatureCelsius}°C fan ${state.fanSpeed} swing ${state.verticalSwing}"

    fun buildBytes(state: AcState): IntArray {
        val code = IntArray(8)
        code[0] = 0x01
        code[1] = (fanNibble(state.fanSpeed) shl 4) or modeNibble(state.mode)
        code[2] = (2 * state.temperatureCelsius) and 0xFF
        code[5] = if (state.power) 0x30 else 0xC0
        code[6] = if (state.verticalSwing == SwingMode.AUTO) 0xC0 else 0x00
        code[7] = checksum(code)
        return code
    }

    fun checksum(code: IntArray): Int {
        var sum = 0
        for (i in 0 until 7) sum += code[i]
        val temp = code[2] / 2
        val fan = (code[1] and 0xF0) shr 4
        val swing = (code[6] and 0xC0) != 0
        var value = sum - (0xF * (3 + temp / 8) + fan * 0xF + if (swing) 0xB4 else 0)
        if (code[5] and 0xF0 == 0xC0) value -= OFF_CHECKSUM_OFFSET
        return value and 0xFF
    }

    fun toPattern(bytes: IntArray): IrPattern {
        val b = TimingBuilder()
        repeat(2) { r ->
            b.beginFrame().mark(HDR_MARK).space(HDR_SPACE)
            for (bit in Bits.lsbFirst(bytes)) {
                if (bit) b.mark(LONG).space(SHORT) else b.mark(SHORT).space(LONG)
            }
            if (r == 0) b.mark(LONG).space(REPEAT_GAP)
        }
        b.mark(LONG)
        return b.build(CARRIER_HZ)
    }

    private fun fanNibble(fan: FanSpeed) = when (fan) {
        FanSpeed.LOW -> 1
        FanSpeed.MEDIUM -> 2
        FanSpeed.HIGH -> 3
        FanSpeed.AUTO -> 4
    }

    private fun modeNibble(mode: AcMode) = when (mode) {
        AcMode.COOL -> 1
        AcMode.HEAT -> 2
        AcMode.FAN_ONLY -> 3
        AcMode.DRY -> 4
        AcMode.AUTO -> 1
    }

    companion object {
        const val ID = "tadiran64"
        const val CARRIER_HZ = 38_000
        const val HDR_MARK = 8000
        const val HDR_SPACE = 4000
        const val LONG = 1618
        const val SHORT = 545
        const val REPEAT_GAP = 31000
        const val OFF_CHECKSUM_OFFSET = 0x87
    }
}
