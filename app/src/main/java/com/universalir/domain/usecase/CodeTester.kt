package com.universalir.domain.usecase

import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.LoadResult
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.ir.format.CommandNames
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.ac.AcMode
import com.universalir.ir.protocol.ac.AcState
import com.universalir.ir.protocol.ac.FanSpeed
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** A code set made ready for a one-command trial before the user saves it. */
data class PreparedTest(
    val set: LoadedCodeSet,
    /** Command name for discrete remotes, null for AC code sets. */
    val command: String?,
    /** Safe AC state for AC code sets, null otherwise. */
    val acState: AcState?,
) {
    val label: String
        get() = acState?.let { "AC ON · ${it.mode} · ${it.temperatureCelsius} °C · fan ${it.fanSpeed}" } ?: command ?: "-"
}

sealed interface PrepareResult {
    data class Ready(val test: PreparedTest) : PrepareResult
    data class Error(val reason: String) : PrepareResult
}

/**
 * Shared by the code wizard and the "Test" button on code-set cards: picks the safest command
 * that still gives a visible/audible reaction and sends it once per user action.
 */
@Singleton
class CodeTester @Inject constructor(
    private val codes: CodeRepository,
    private val remote: RemoteController,
) {
    private var lastSendAt = 0L

    suspend fun prepare(codeSetId: String): PrepareResult = when (val r = codes.load(codeSetId)) {
        is LoadResult.Error -> PrepareResult.Error(r.reason)
        is LoadResult.Ok -> when (val set = r.set) {
            is LoadedCodeSet.CommandSet -> testCommand(set)?.let { PrepareResult.Ready(PreparedTest(set, it, null)) }
                ?: PrepareResult.Error("This code set has no commands.")
            else -> acTestState(set)?.let { PrepareResult.Ready(PreparedTest(set, null, it)) }
                ?: PrepareResult.Error("No usable test state in this code set.")
        }
    }

    /** Returns null when called again within 1.5 s, so a nervous double tap cannot spam the device. */
    suspend fun send(test: PreparedTest): SendOutcome? {
        val now = System.currentTimeMillis()
        if (now - lastSendAt < MIN_INTERVAL_MS) return null
        lastSendAt = now
        return if (test.acState != null) remote.sendAc(test.set, test.acState, previous = null)
        else remote.sendCommand(test.set, test.command!!)
    }

    /** Undoes an AC trial by sending the matching OFF state. */
    suspend fun turnAcOff(test: PreparedTest): SendOutcome? =
        test.acState?.let { remote.sendAc(test.set, it.copy(power = false), it) }

    private fun testCommand(set: LoadedCodeSet.CommandSet): String? =
        testCommandOrder(set.codes.category).firstOrNull { it in set.commandNames } ?: set.codes.commands.firstOrNull()?.name

    companion object {
        const val MIN_INTERVAL_MS = 1500L

        /** Safest command with a visible/audible reaction, per category. Lights test with "on". */
        fun testCommandOrder(category: DeviceCategory): List<String> = when (category) {
            DeviceCategory.LIGHT -> listOf(CommandNames.POWER_ON, CommandNames.POWER, CommandNames.BRIGHTNESS_UP)
            else -> listOf(CommandNames.MUTE, CommandNames.VOL_UP, CommandNames.POWER, CommandNames.POWER_ON, CommandNames.SPEED, CommandNames.POWER_OFF)
        }
    }

    private fun acTestState(set: LoadedCodeSet): AcState? = when (set) {
        is LoadedCodeSet.AcProtocolSet -> set.protocol.safeTestState()
        is LoadedCodeSet.AcTableSet -> {
            val t = set.table
            val mode = if (AcMode.COOL in t.modes) AcMode.COOL else t.modes.firstOrNull()
            mode?.let { m ->
                val fans = t.fans(m)
                val fan = if (FanSpeed.AUTO in fans) FanSpeed.AUTO else fans.firstOrNull() ?: return@let null
                val temp = t.temps(m, fan).minByOrNull { abs(it - 24) } ?: return@let null
                AcState(true, m, temp, fan)
            }
        }
        is LoadedCodeSet.CommandSet -> null
    }
}
