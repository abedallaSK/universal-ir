package com.universalir.domain.usecase

import com.universalir.domain.model.LoadedCodeSet
import com.universalir.ir.protocol.ac.AcCapabilities
import com.universalir.ir.protocol.ac.AcFeature
import com.universalir.ir.protocol.ac.AcMode
import com.universalir.ir.protocol.ac.AcState
import com.universalir.ir.protocol.ac.FanSpeed
import com.universalir.ir.protocol.ac.PowerControl
import com.universalir.ir.protocol.ac.SwingMode

/** Per mode+fan temperature list for captured AC tables. */
typealias AcTableTemps = (AcMode, FanSpeed) -> List<Int>

/**
 * What an AC code set can express, shared by the AC remote screen and the home-screen widget
 * so both clamp states the same way.
 */
object AcStateRules {

    /** Capabilities of [set] plus, for captured tables, the temperatures per mode/fan; null if not an AC set. */
    fun capabilitiesOf(set: LoadedCodeSet): Pair<AcCapabilities, AcTableTemps?>? = when (set) {
        is LoadedCodeSet.AcProtocolSet -> set.protocol.capabilities to null
        is LoadedCodeSet.AcTableSet -> {
            val t = set.table
            if (t.modes.isEmpty()) null else {
                val allTemps = t.modes.flatMap { m -> t.fans(m).flatMap { f -> t.temps(m, f) } }
                AcCapabilities(
                    temperatureRange = (allTemps.minOrNull() ?: 16)..(allTemps.maxOrNull() ?: 30),
                    modes = t.modes, fanSpeeds = t.modes.flatMap { t.fans(it) }.toSet(),
                    fanSpeedsByMode = t.modes.associateWith { t.fans(it) },
                    powerControl = PowerControl.ABSOLUTE,
                ) to { m: AcMode, f: FanSpeed -> t.temps(m, f) }
            }
        }
        is LoadedCodeSet.CommandSet -> null
    }

    /** Keeps the requested state inside what the code set can actually express. */
    fun coerce(s: AcState, caps: AcCapabilities, temps: AcTableTemps?): AcState {
        val mode = if (s.mode in caps.modes) s.mode else caps.modes.first()
        val fans = caps.fanSpeedsFor(mode)
        val fan = if (s.fanSpeed in fans) s.fanSpeed else fans.firstOrNull() ?: FanSpeed.AUTO
        val temp = temps?.invoke(mode, fan)?.takeIf { it.isNotEmpty() }?.let { list -> list.minBy { kotlin.math.abs(it - s.temperatureCelsius) } }
            ?: s.temperatureCelsius.coerceIn(caps.temperatureRange)
        return s.copy(
            mode = mode, fanSpeed = fan, temperatureCelsius = temp,
            verticalSwing = if (s.verticalSwing in caps.verticalSwing) s.verticalSwing else SwingMode.OFF,
            horizontalSwing = if (s.horizontalSwing in caps.horizontalSwing) s.horizontalSwing else SwingMode.OFF,
            turbo = s.turbo.takeIf { AcFeature.TURBO in caps.features }, quiet = s.quiet.takeIf { AcFeature.QUIET in caps.features },
            sleep = s.sleep.takeIf { AcFeature.SLEEP in caps.features }, light = s.light.takeIf { AcFeature.LIGHT in caps.features },
            iFeel = s.iFeel.takeIf { AcFeature.IFEEL in caps.features },
        )
    }

    /** Next settable temperature one step up/down, or null when already at the limit (or temperature is ignored). */
    fun stepTemperature(s: AcState, caps: AcCapabilities, temps: AcTableTemps?, up: Boolean): Int? {
        if (s.mode in caps.temperatureIgnoredIn) return null
        val list = temps?.invoke(s.mode, s.fanSpeed)
        return if (list != null) {
            if (up) list.filter { it > s.temperatureCelsius }.minOrNull() else list.filter { it < s.temperatureCelsius }.maxOrNull()
        } else {
            val next = s.temperatureCelsius + if (up) 1 else -1
            next.takeIf { it in caps.temperatureRange }
        }
    }
}
