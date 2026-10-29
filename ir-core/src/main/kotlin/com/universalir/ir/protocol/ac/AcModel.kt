package com.universalir.ir.protocol.ac

import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.IrProtocol
import kotlinx.serialization.Serializable

@Serializable
enum class AcMode { AUTO, COOL, HEAT, DRY, FAN_ONLY }

@Serializable
enum class FanSpeed { AUTO, LOW, MEDIUM, HIGH }

@Serializable
enum class SwingMode { OFF, AUTO, POSITION }

enum class AcFeature { TURBO, SLEEP, ECO, QUIET, IFEEL, LIGHT, DISPLAY }

/** How a protocol expresses power. Drives what the UI warns about. */
enum class PowerControl {
    /** Every frame states on/off explicitly. */
    ABSOLUTE,

    /** Power is a toggle; the app must track the assumed state and can drift from reality. */
    TOGGLE,

    /** Power is a toggle on the wire but a documented sequence forces a known result. */
    TOGGLE_WITH_FORCING_SEQUENCE,
}

/**
 * The state the user requested. The app cannot read the real AC state over IR, so this is always
 * "last requested", never confirmed telemetry.
 */
@Serializable
data class AcState(
    val power: Boolean = false,
    val mode: AcMode = AcMode.COOL,
    val temperatureCelsius: Int = 24,
    val fanSpeed: FanSpeed = FanSpeed.AUTO,
    val verticalSwing: SwingMode = SwingMode.OFF,
    val horizontalSwing: SwingMode = SwingMode.OFF,
    val turbo: Boolean? = null,
    val sleep: Boolean? = null,
    val eco: Boolean? = null,
    val quiet: Boolean? = null,
    val iFeel: Boolean? = null,
    val light: Boolean? = null,
    val display: Boolean? = null,
    val modelSpecificFields: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Key for the remote-sensor temperature used by I Feel. */
        const val FIELD_IFEEL_SENSOR_C = "ifeel.sensorC"
    }
}

data class AcCapabilities(
    val temperatureRange: IntRange,
    val modes: Set<AcMode>,
    val fanSpeeds: Set<FanSpeed>,
    /** Fan speeds actually allowed per mode when narrower than [fanSpeeds]. */
    val fanSpeedsByMode: Map<AcMode, Set<FanSpeed>> = emptyMap(),
    /** Modes where the unit ignores the set temperature. */
    val temperatureIgnoredIn: Set<AcMode> = emptySet(),
    val verticalSwing: Set<SwingMode> = setOf(SwingMode.OFF),
    val horizontalSwing: Set<SwingMode> = setOf(SwingMode.OFF),
    val features: Set<AcFeature> = emptySet(),
    val powerControl: PowerControl,
    val extraCommands: List<AcExtraCommand> = emptyList(),
) {
    fun fanSpeedsFor(mode: AcMode): Set<FanSpeed> = fanSpeedsByMode[mode] ?: fanSpeeds
}

/** A protocol command that is not part of the complete state (e.g. a swing toggle). */
data class AcExtraCommand(val id: String, val label: String, val description: String)

interface AcStateEncoder {
    fun validateState(state: AcState): List<String>

    /**
     * Encodes [state]. [previous] is the last state this app sent (null if unknown), needed by
     * protocols whose power command is a toggle.
     */
    fun encodeCompleteState(state: AcState, previous: AcState?): EncodeResult

    fun describeState(state: AcState): String
}

interface AcProtocol : IrProtocol, AcStateEncoder {
    val capabilities: AcCapabilities

    fun encodeExtra(commandId: String, state: AcState): EncodeResult =
        EncodeResult.Failure(listOf("Command '$commandId' is not supported by ${info.displayName}."))

    /** A safe, reversible state for the code-finding wizard. */
    fun safeTestState(): AcState {
        val caps = capabilities
        val mode = if (AcMode.COOL in caps.modes) AcMode.COOL else caps.modes.first()
        val temp = 24.coerceIn(caps.temperatureRange)
        val fans = caps.fanSpeedsFor(mode)
        val fan = if (FanSpeed.AUTO in fans) FanSpeed.AUTO else fans.first()
        return AcState(power = true, mode = mode, temperatureCelsius = temp, fanSpeed = fan)
    }

    override fun validateState(state: AcState): List<String> = defaultValidation(capabilities, state)
}

fun defaultValidation(caps: AcCapabilities, state: AcState): List<String> {
    val issues = mutableListOf<String>()
    if (state.mode !in caps.modes) issues += "Mode ${state.mode} is not supported."
    if (state.mode !in caps.temperatureIgnoredIn && state.temperatureCelsius !in caps.temperatureRange) {
        issues += "Temperature ${state.temperatureCelsius} °C is outside ${caps.temperatureRange.first}–${caps.temperatureRange.last} °C."
    }
    if (state.fanSpeed !in caps.fanSpeedsFor(state.mode)) {
        issues += "Fan speed ${state.fanSpeed} is not available in ${state.mode}."
    }
    if (state.verticalSwing !in caps.verticalSwing) issues += "Vertical swing ${state.verticalSwing} is not supported."
    if (state.horizontalSwing !in caps.horizontalSwing) issues += "Horizontal swing ${state.horizontalSwing} is not supported."
    fun feature(flag: Boolean?, f: AcFeature) {
        if (flag == true && f !in caps.features) issues += "$f is not supported by this protocol."
    }
    feature(state.turbo, AcFeature.TURBO)
    feature(state.sleep, AcFeature.SLEEP)
    feature(state.eco, AcFeature.ECO)
    feature(state.quiet, AcFeature.QUIET)
    feature(state.iFeel, AcFeature.IFEEL)
    feature(state.light, AcFeature.LIGHT)
    feature(state.display, AcFeature.DISPLAY)
    if (state.iFeel == true && state.modelSpecificFields[AcState.FIELD_IFEEL_SENSOR_C]?.toIntOrNull() == null) {
        issues += "I Feel needs a sensor temperature."
    }
    return issues
}
