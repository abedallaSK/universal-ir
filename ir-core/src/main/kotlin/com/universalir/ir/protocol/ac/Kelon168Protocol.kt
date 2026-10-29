package com.universalir.ir.protocol.ac

import com.universalir.ir.core.Bits
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
 * KELON168: 21 bytes, three frames (6/8/7 bytes), LSB first, 343 timing entries.
 *
 * Byte tables and checksums follow Aikidyst/kelon168-ir-homeassistant (MIT, `kelon168.py`), which
 * was reverse engineered from captures of a Kelon OEM unit sold as Electra/Tadiran in Israel.
 * The frame split (6/8/7) and the 0x83 0x06 preamble agree with IRremoteESP8266's
 * independent `IRKelon168Ac`.
 *
 * Power is a toggle on the wire. The source documents that the TURBO frame always powers the unit
 * on, so: power on = TURBO, then state; power off = TURBO, then power-toggle frame.
 */
class Kelon168Protocol(
    /** Pause between the messages of a power sequence. Not specified by the source. */
    private val sequencePauseMs: Int = 1000,
) : AcProtocol {

    override val info = ProtocolInfo(
        id = ID,
        displayName = "KELON168 (Kelon OEM / Electra / Tadiran / Tornado)",
        deviceCategories = setOf(DeviceCategory.AIR_CONDITIONER),
        carrierHz = CARRIER_HZ,
        frameDescription = "Header $HDR_MARK/$HDR_SPACE us, bit mark $BIT_MARK us, one $ONE_SPACE us, " +
            "zero $ZERO_SPACE us, LSB first. Frames of 6, 8 and 7 bytes separated by $GAP us. " +
            "Checksums: byte13 = XOR(bytes 0..12) ^ 0x85, byte20 = byte15 ^ 0x08.",
        completeState = true,
        sources = listOf(
            SourceRef("Aikidyst/kelon168-ir-homeassistant", "https://github.com/Aikidyst/kelon168-ir-homeassistant", "MIT", "kelon168.py byte tables, timings, power sequences"),
            SourceRef("IRremoteESP8266 ir_Kelon.cpp", "https://github.com/crankyoldgit/IRremoteESP8266/blob/master/src/ir_Kelon.cpp", "LGPL-2.1", "Independent confirmation of 168-bit/3-section framing and 38 kHz carrier"),
        ),
        verification = VerificationLevel.WAVEFORM_VALIDATED,
        evidence = listOf(
            "Reproduces all 10 byte vectors from kelon168.py self-test (captured states).",
            "SmartIR 1621 (Tornado Super Legend 40): 118/118 clean captured frames have the 0x83 0x06 preamble, 6/8/7 framing and both checksums.",
        ),
        warnings = listOf(
            "Power is a toggle on the wire; the app uses the TURBO-forcing sequence documented by the source.",
            "Swing, sleep and timer are not decoded by the source and are not offered.",
            "Explicit fan speeds in HEAT reuse COOL session bytes (source: 'likely works').",
            "Tornado Super Legend 40 captures use different byte 6/7 values (0x80/varying) than the Aikidyst unit (0x8C); the byte tables are only confirmed for the Aikidyst unit.",
            "Delay between sequence frames is not documented; ${sequencePauseMs} ms is used.",
        ),
    )

    override val capabilities = AcCapabilities(
        temperatureRange = 16..30,
        modes = setOf(AcMode.COOL, AcMode.HEAT, AcMode.DRY, AcMode.FAN_ONLY),
        fanSpeeds = setOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH),
        fanSpeedsByMode = mapOf(AcMode.DRY to setOf(FanSpeed.AUTO)),
        temperatureIgnoredIn = setOf(AcMode.DRY, AcMode.FAN_ONLY),
        features = setOf(AcFeature.TURBO, AcFeature.IFEEL),
        powerControl = PowerControl.TOGGLE_WITH_FORCING_SEQUENCE,
    )

    override fun encodeCompleteState(state: AcState, previous: AcState?): EncodeResult {
        val issues = validateState(state)
        if (issues.isNotEmpty()) return EncodeResult.Failure(issues)

        val turbo = buildState(state, turbo = true)
        val messages = mutableListOf<Pair<String, IntArray>>()
        when {
            !state.power -> {
                messages += "turbo (forces on)" to turbo
                messages += "power toggle" to buildPowerToggle(state)
            }
            state.turbo == true -> messages += "turbo" to turbo
            previous == null || !previous.power -> {
                messages += "turbo (forces on)" to turbo
                messages += "state" to buildState(state, turbo = false)
            }
            else -> messages += "state" to buildState(state, turbo = false)
        }
        return EncodeResult.Success(
            transmission = IrTransmission(
                messages.mapIndexed { i, (label, bytes) ->
                    IrMessage(label, toPattern(bytes), if (i < messages.lastIndex) sequencePauseMs else 0)
                },
            ),
            payloads = messages.map { (label, bytes) -> "$label: ${Bits.hex(bytes)}" },
            summary = describeState(state),
        )
    }

    override fun describeState(state: AcState): String =
        if (!state.power) "OFF (turbo + power toggle)"
        else "${state.mode} ${state.temperatureCelsius}°C fan ${state.fanSpeed}" +
            (if (state.turbo == true) " TURBO" else "") + (if (state.iFeel == true) " I-Feel" else "")

    /** Port of `build_state` in kelon168.py. */
    fun buildState(state: AcState, turbo: Boolean): IntArray {
        val s = IntArray(21)
        s[0] = 0x83; s[1] = 0x06; s[18] = 0x08
        val fan = state.fanSpeed
        when {
            turbo -> {
                s[2] = 0x01; s[3] = 0x02; s[5] = 0x90; s[6] = 0x8C; s[7] = 0x38; s[15] = 0x04
            }
            state.mode == AcMode.DRY -> {
                s[2] = 0x00; s[3] = (7 shl 4) or 0x3; s[6] = 0x8C; s[7] = 0x28; s[15] = 0x06
            }
            state.mode == AcMode.FAN_ONLY -> {
                s[2] = fanOnlyByte2(fan); s[3] = (7 shl 4) or 0x4; s[6] = 0x8C
                s[7] = byte7(AcMode.FAN_ONLY, fan) ?: 0x2A; s[15] = 0x06
            }
            else -> {
                val temp = state.temperatureCelsius.coerceIn(16, 30)
                s[2] = normalByte2(fan)
                s[3] = ((temp - 16) shl 4) or modeLow(state.mode)
                s[6] = 0x8C
                s[7] = byte7(state.mode, fan) ?: 0x05
                s[15] = if (fan == FanSpeed.AUTO) 0x02 else 0x11
            }
        }
        val sensor = state.modelSpecificFields[AcState.FIELD_IFEEL_SENSOR_C]?.toIntOrNull()
        if (state.iFeel == true && sensor != null && !turbo) {
            s[6] = 0x8D; s[11] = 0x80; s[12] = sensor and 0xFF; s[15] = 0x0D
        }
        applyChecksums(s)
        return s
    }

    /** Port of `build_power_raw` in kelon168.py. */
    fun buildPowerToggle(state: AcState): IntArray {
        val s = IntArray(21)
        val temp = state.temperatureCelsius.coerceIn(16, 30)
        s[0] = 0x83; s[1] = 0x06
        s[2] = when (state.fanSpeed) {
            FanSpeed.AUTO -> 0x04
            FanSpeed.LOW -> 0x07
            FanSpeed.MEDIUM -> 0x06
            FanSpeed.HIGH -> 0x05
        }
        s[3] = ((temp - 16) shl 4) or modeLow(state.mode)
        s[6] = 0x95; s[7] = 0x24; s[15] = 0x01; s[18] = 0x08
        applyChecksums(s)
        return s
    }

    fun toPattern(bytes: IntArray): IrPattern {
        require(bytes.size == 21)
        val b = TimingBuilder()
        b.beginFrame().mark(HDR_MARK).space(HDR_SPACE)
        b.pulseDistanceBits(Bits.lsbFirst(bytes.copyOfRange(0, 6)), BIT_MARK, ONE_SPACE, ZERO_SPACE)
        b.mark(BIT_MARK).space(GAP)
        b.beginFrame().pulseDistanceBits(Bits.lsbFirst(bytes.copyOfRange(6, 14)), BIT_MARK, ONE_SPACE, ZERO_SPACE)
        b.mark(BIT_MARK).space(GAP)
        b.beginFrame().pulseDistanceBits(Bits.lsbFirst(bytes.copyOfRange(14, 21)), BIT_MARK, ONE_SPACE, ZERO_SPACE)
        b.mark(BIT_MARK)
        return b.build(CARRIER_HZ)
    }

    private fun applyChecksums(s: IntArray) {
        var x = 0
        for (i in 0 until 13) x = x xor s[i]
        s[13] = x xor 0x85
        s[20] = s[15] xor 0x08
    }

    private fun modeLow(mode: AcMode) = when (mode) {
        AcMode.COOL -> 0x2
        AcMode.HEAT -> 0x0
        AcMode.DRY -> 0x3
        AcMode.FAN_ONLY -> 0x4
        AcMode.AUTO -> 0x2
    }

    private fun normalByte2(fan: FanSpeed) = when (fan) {
        FanSpeed.AUTO -> 0x00
        FanSpeed.LOW -> 0x03
        FanSpeed.MEDIUM -> 0x02
        FanSpeed.HIGH -> 0x01
    }

    private fun fanOnlyByte2(fan: FanSpeed) = if (fan == FanSpeed.AUTO) 0x01 else normalByte2(fan)

    private fun byte7(mode: AcMode, fan: FanSpeed): Int? = when (mode) {
        AcMode.COOL -> when (fan) { FanSpeed.AUTO -> 0x05; FanSpeed.LOW -> 0x32; else -> 0x2E }
        AcMode.HEAT -> when (fan) { FanSpeed.AUTO -> 0x25; FanSpeed.LOW -> 0x32; else -> 0x2E }
        AcMode.DRY -> if (fan == FanSpeed.AUTO) 0x28 else null
        AcMode.FAN_ONLY -> when (fan) { FanSpeed.AUTO -> 0x2A; FanSpeed.LOW -> 0x32; else -> 0x2E }
        AcMode.AUTO -> null
    }

    companion object {
        const val ID = "kelon168"
        const val CARRIER_HZ = 38_000
        const val HDR_MARK = 8308
        const val HDR_SPACE = 4527
        const val BIT_MARK = 554
        const val ONE_SPACE = 1712
        const val ZERO_SPACE = 554
        const val GAP = 8308
    }
}
