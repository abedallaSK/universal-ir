package com.universalir.data.wifi

// Shared model for every Wi-Fi air conditioner (Gree/Tadiran LAN, Daikin LAN, Electra Smart cloud, Sensibo cloud),
// so one AC screen drives them all. Pure Kotlin except where noted.

enum class WifiAcMode { AUTO, COOL, HEAT, DRY, FAN }
enum class WifiFan { AUTO, LOW, MEDIUM, HIGH }

/** The AC state as the unit (or its cloud) reports it. Temperatures are whole °C. */
data class WifiAcState(
    val power: Boolean = false,
    val mode: WifiAcMode = WifiAcMode.COOL,
    val targetC: Int = 24,
    val fan: WifiFan = WifiFan.AUTO,
    val swing: Boolean = false,
)

data class WifiAcCaps(
    val modes: List<WifiAcMode> = WifiAcMode.entries,
    val fans: List<WifiFan> = WifiFan.entries,
    val tempRange: IntRange = 16..30,
    val swing: Boolean = true,
    /** Modes where the set point doesn't apply (shown as "--"). */
    val tempIgnoredIn: Set<WifiAcMode> = setOf(WifiAcMode.FAN),
)

/** One reading: real state, what this unit supports, and the room temperature when the unit reports it. */
data class WifiAcStatus(val state: WifiAcState, val caps: WifiAcCaps, val roomC: Double? = null)

/** A Wi-Fi AC. Implementations never throw to callers; [write] sends the complete desired state. */
interface WifiAcController {
    suspend fun read(): WifiResult<WifiAcStatus>
    suspend fun write(state: WifiAcState): WifiResult<Unit>
    fun close() {}
}

object WifiAcRules {
    /** Clamps a requested state to what [caps] allow. */
    fun coerce(s: WifiAcState, caps: WifiAcCaps): WifiAcState = s.copy(
        mode = if (s.mode in caps.modes || caps.modes.isEmpty()) s.mode else caps.modes.first(),
        targetC = if (caps.tempRange.isEmpty()) s.targetC else s.targetC.coerceIn(caps.tempRange),
        fan = if (s.fan in caps.fans || caps.fans.isEmpty()) s.fan else caps.fans.first(),
        swing = s.swing && caps.swing,
    )
}

// ------------------------------------------------------------------ Gree / Tadiran (LAN, UDP 7000)

/** Gree-module ACs (also sold as Tadiran, Cooper&Hunter, Sinclair…). Mod 0 auto,1 cool,2 dry,3 fan,4 heat; fan 0/1/3/5. */
class GreeAcController(private val gree: GreeController) : WifiAcController {
    private val caps = WifiAcCaps(tempRange = GreeController.TEMP_RANGE)

    override suspend fun read(): WifiResult<WifiAcStatus> = when (val r = gree.status()) {
        is WifiResult.Err -> r
        is WifiResult.Ok -> WifiResult.Ok(WifiAcStatus(fromGree(r.value), caps))
    }

    override suspend fun write(state: WifiAcState): WifiResult<Unit> = gree.set(linkedMapOf(
        GreeController.POW to if (state.power) 1 else 0,
        GreeController.MOD to modeToGree(state.mode),
        GreeController.SET_TEM to state.targetC.coerceIn(GreeController.TEMP_RANGE),
        GreeController.WD_SPD to fanToGree(state.fan),
        GreeController.SW_UP_DN to if (state.swing) 1 else 0,
    ))

    companion object {
        fun fromGree(g: GreeState) = WifiAcState(
            power = g.power,
            mode = when (g.mode) { 0 -> WifiAcMode.AUTO; 2 -> WifiAcMode.DRY; 3 -> WifiAcMode.FAN; 4 -> WifiAcMode.HEAT; else -> WifiAcMode.COOL },
            targetC = g.temperature,
            fan = when (g.fan) { 0 -> WifiFan.AUTO; 1, 2 -> WifiFan.LOW; 3, 4 -> WifiFan.MEDIUM; else -> WifiFan.HIGH },
            swing = g.swing,
        )

        fun modeToGree(m: WifiAcMode) = when (m) { WifiAcMode.AUTO -> 0; WifiAcMode.COOL -> 1; WifiAcMode.DRY -> 2; WifiAcMode.FAN -> 3; WifiAcMode.HEAT -> 4 }
        fun fanToGree(f: WifiFan) = when (f) { WifiFan.AUTO -> 0; WifiFan.LOW -> 1; WifiFan.MEDIUM -> 3; WifiFan.HIGH -> 5 }
    }
}
