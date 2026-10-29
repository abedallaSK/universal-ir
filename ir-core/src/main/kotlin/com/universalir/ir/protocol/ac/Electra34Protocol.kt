package com.universalir.ir.protocol.ac

import com.universalir.ir.core.IrMessage
import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.IrTransmission
import com.universalir.ir.core.TimingBuilder
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.ProtocolInfo
import com.universalir.ir.protocol.SourceRef
import com.universalir.ir.protocol.VerificationLevel

/**
 * Electra 34-bit Manchester protocol.
 *
 * Two independent sources describe the same frame:
 *  - nryhbhue/Electra-AC-Remote (Unlicense, 2017): C encoder for a split unit.
 *  - danielrosehill/electra-central-ac-ir-codes (MIT, 2026): decoded from captures of a ducted
 *    Electra/Tadiran central unit and verified on that unit.
 *
 * Core bits (index 0 sent first): 0 = power toggle, 1..3 = mode (cool 001, heat 010),
 * 4..5 = fan (low 00, med 01, high 10, auto 11), 7..8 = swing, 11..14 = °C − 15, 32 = 1.
 * No checksum. Half-bit 1000 us; bit 0 = mark,space; bit 1 = space,mark. Header 3000/3000 us.
 * The frame is sent three times back to back followed by a 4000 us mark.
 */
class Electra34Protocol(private val sequencePauseMs: Int = 600) : AcProtocol {

    override val info = ProtocolInfo(
        id = ID,
        displayName = "Electra 34-bit Manchester (split & central)",
        deviceCategories = setOf(DeviceCategory.AIR_CONDITIONER),
        carrierHz = CARRIER_HZ,
        frameDescription = "Header $HEADER/$HEADER us, Manchester half-bit $HALF_BIT us, 34 bits MSB-field order, " +
            "frame repeated 3×, trailing $TRAILER us mark. No checksum.",
        completeState = true,
        sources = listOf(
            SourceRef("nryhbhue/Electra-AC-Remote", "https://github.com/nryhbhue/Electra-AC-Remote", "Unlicense", "electraAcRemoteEncoder.c"),
            SourceRef("danielrosehill/electra-central-ac-ir-codes", "https://github.com/danielrosehill/electra-central-ac-ir-codes", "MIT", "Field map + real captures"),
            SourceRef("SmartIR climate 1940 (Electra)", "https://github.com/zephyrx47/SmartIR", "MIT", "Captured frames use this 3000 us Manchester format"),
        ),
        verification = VerificationLevel.WAVEFORM_VALIDATED,
        evidence = listOf(
            "Encoder bits equal the 34-bit cores decoded from 8 real central-AC captures (anchor_20, anchor_24, fan_*, heat_low_24, power_toggle).",
            "Field layout from the 2017 C encoder and the 2026 captures agree independently.",
        ),
        warnings = listOf(
            "Power is a pure toggle: the app tracks the assumed state and may drift if the physical remote is used.",
            "Only COOL and HEAT are documented; DRY/FAN/AUTO are not offered.",
            "State frames do not power the unit on; turning on sends toggle then state.",
        ),
    )

    override val capabilities = AcCapabilities(
        temperatureRange = 16..30,
        modes = setOf(AcMode.COOL, AcMode.HEAT),
        fanSpeeds = setOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH),
        verticalSwing = setOf(SwingMode.OFF, SwingMode.AUTO),
        powerControl = PowerControl.TOGGLE,
        extraCommands = listOf(
            AcExtraCommand(EXTRA_TOGGLE, "Power toggle only", "Sends only the power toggle. Use it to re-synchronise if the unit was switched with its own remote."),
        ),
    )

    override fun encodeCompleteState(state: AcState, previous: AcState?): EncodeResult {
        val issues = validateState(state)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues)
        val assumedOn = previous?.power ?: false
        val frames = mutableListOf<Pair<String, Long>>()
        when {
            state.power && !assumedOn -> {
                frames += "power toggle (on)" to coreBits(state, toggle = true)
                frames += "state" to coreBits(state, toggle = false)
            }
            !state.power && assumedOn -> frames += "power toggle (off)" to coreBits(state, toggle = true)
            !state.power -> return EncodeResult.Failure(listOf("The unit is already assumed off; use 'Power toggle only' to resynchronise."))
            else -> frames += "state" to coreBits(state, toggle = false)
        }
        return success(frames, describeState(state))
    }

    override fun encodeExtra(commandId: String, state: AcState): EncodeResult =
        if (commandId == EXTRA_TOGGLE) success(listOf("power toggle" to coreBits(state, toggle = true)), "Power toggle")
        else super.encodeExtra(commandId, state)

    private fun success(frames: List<Pair<String, Long>>, summary: String) = EncodeResult.Success(
        IrTransmission(frames.mapIndexed { i, (label, bits) ->
            IrMessage(label, toPattern(bits), if (i < frames.lastIndex) sequencePauseMs else 0)
        }),
        frames.map { (label, bits) -> "$label: ${bitString(bits)}" },
        summary,
    )

    override fun describeState(state: AcState) =
        if (!state.power) "OFF (toggle)" else "${state.mode} ${state.temperatureCelsius}°C fan ${state.fanSpeed}"

    /** Returns the 34 core bits with index 0 in bit 33 (sent first). */
    fun coreBits(state: AcState, toggle: Boolean): Long {
        var v = 0L
        fun put(index: Int, width: Int, value: Int) {
            for (k in 0 until width) {
                val bit = (value shr (width - 1 - k)) and 1
                if (bit == 1) v = v or (1L shl (33 - (index + k)))
            }
        }
        put(0, 1, if (toggle) 1 else 0)
        put(1, 3, if (state.mode == AcMode.HEAT) 0b010 else 0b001)
        put(4, 2, when (state.fanSpeed) { FanSpeed.LOW -> 0; FanSpeed.MEDIUM -> 1; FanSpeed.HIGH -> 2; FanSpeed.AUTO -> 3 })
        put(7, 2, if (state.verticalSwing == SwingMode.AUTO) 0b01 else 0b00)
        put(11, 4, state.temperatureCelsius.coerceIn(16, 30) - 15)
        put(32, 1, 1)
        return v
    }

    fun bitString(bits: Long): String = (33 downTo 0).joinToString("") { ((bits shr it) and 1L).toString() }

    fun toPattern(bits: Long): IrPattern {
        val b = TimingBuilder()
        repeat(REPEATS) {
            b.beginFrame().mark(HEADER).space(HEADER)
            for (i in 33 downTo 0) {
                if ((bits shr i) and 1L == 0L) b.mark(HALF_BIT).space(HALF_BIT) else b.space(HALF_BIT).mark(HALF_BIT)
            }
        }
        b.mark(TRAILER)
        return b.build(CARRIER_HZ)
    }

    companion object {
        const val ID = "electra34"
        const val EXTRA_TOGGLE = "power_toggle"
        const val CARRIER_HZ = 38_000
        const val HEADER = 3000
        const val HALF_BIT = 1000
        const val TRAILER = 4000
        const val REPEATS = 3
    }
}
