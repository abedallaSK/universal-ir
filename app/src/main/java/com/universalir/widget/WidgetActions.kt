package com.universalir.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.universalir.core.ui.describeOutcome
import com.universalir.data.repository.LoadResult
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.domain.usecase.AcStateRules
import com.universalir.domain.usecase.SendOutcome
import com.universalir.ir.protocol.ac.AcState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object WidgetParams {
    val DeviceId = ActionParameters.Key<Long>("deviceId")
    val Command = ActionParameters.Key<String>("command")
    val AcOp = ActionParameters.Key<String>("acOp")
    val FavoriteId = ActionParameters.Key<Long>("favoriteId")

    const val AC_POWER = "power"
    const val AC_UP = "up"
    const val AC_DOWN = "down"
}

/** Serialises widget sends so quick taps never interleave read-modify-write of the AC state. */
private val sendLock = Mutex()

private val codeSetUnavailable = SendOutcome.EncodeError(listOf("Code set unavailable"))

private suspend fun reportFailure(context: Context, outcome: SendOutcome) {
    if (outcome !is SendOutcome.Success) Widgets.toast(context, describeOutcome(context, outcome))
}

/** Sends one named command of a device's code set. */
class SendCommandAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val deviceId = parameters[WidgetParams.DeviceId] ?: return
        val command = parameters[WidgetParams.Command] ?: return
        val deps = context.widgetDeps()
        val outcome = sendLock.withLock {
            val d = deps.devices().device(deviceId) ?: return
            val set = (deps.codes().load(d.codeSetId) as? LoadResult.Ok)?.set
            val result = if (set == null) codeSetUnavailable else deps.remote().sendCommand(set, command)
            deps.devices().markUsed(d.id)
            result
        }
        reportFailure(context, outcome)
    }
}

/** AC power toggle and temperature steps, using and saving DeviceSettings.acState like the AC remote. */
class AcAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val deviceId = parameters[WidgetParams.DeviceId] ?: return
        val op = parameters[WidgetParams.AcOp] ?: return
        val deps = context.widgetDeps()
        val outcome = sendLock.withLock {
            val d = deps.devices().device(deviceId) ?: return
            val ds = DeviceSettings.decode(d.customSettings) ?: return
            val set = (deps.codes().load(d.codeSetId) as? LoadResult.Ok)?.set ?: return@withLock codeSetUnavailable
            val (caps, temps) = AcStateRules.capabilitiesOf(set) ?: return@withLock codeSetUnavailable
            val current = AcStateRules.coerce(RemoteWidgetLoader.initialAcState(ds, deps.settings().current()), caps, temps)
            val changed = when (op) {
                WidgetParams.AC_POWER -> current.copy(power = !current.power)
                WidgetParams.AC_UP -> current.copy(temperatureCelsius = AcStateRules.stepTemperature(current, caps, temps, up = true) ?: return)
                WidgetParams.AC_DOWN -> current.copy(temperatureCelsius = AcStateRules.stepTemperature(current, caps, temps, up = false) ?: return)
                else -> return
            }
            val next = AcStateRules.coerce(changed, caps, temps)
            val result = deps.remote().sendAc(set, next, ds.lastSentAcState)
            val sent = result is SendOutcome.Success
            deps.devices().saveSettings(d.id, ds.copy(acState = next, lastSentAcState = if (sent) next else ds.lastSentAcState))
            deps.devices().markUsed(d.id)
            result
        }
        // Every widget bound to this AC shows the new target temperature / power.
        DeviceRemoteWidget().updateAll(context)
        reportFailure(context, outcome)
    }
}

/** Runs a saved favorite (button or AC preset) exactly like the Favorites screen. */
class FavoriteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val favoriteId = parameters[WidgetParams.FavoriteId] ?: return
        val deps = context.widgetDeps()
        var acChanged = false
        val outcome = sendLock.withLock {
            val f = deps.devices().favoriteCommands().first().firstOrNull { it.id == favoriteId } ?: return
            val d = deps.devices().device(f.deviceId) ?: return
            val set: LoadedCodeSet = (deps.codes().load(d.codeSetId) as? LoadResult.Ok)?.set ?: return@withLock codeSetUnavailable
            val ds = DeviceSettings.decode(d.customSettings) ?: DeviceSettings()
            val result = if (f.acStateJson != null) {
                val state = runCatching { DeviceSettings.json.decodeFromString(AcState.serializer(), f.acStateJson) }.getOrNull()
                    ?: return@withLock SendOutcome.EncodeError(listOf("Corrupt favourite"))
                deps.remote().sendAc(set, state, ds.lastSentAcState).also {
                    if (it is SendOutcome.Success) {
                        deps.devices().saveSettings(d.id, ds.copy(acState = state, lastSentAcState = state))
                        acChanged = true
                    }
                }
            } else {
                deps.remote().sendCommand(set, f.commandName ?: "")
            }
            deps.devices().markUsed(d.id)
            result
        }
        if (acChanged) DeviceRemoteWidget().updateAll(context)
        reportFailure(context, outcome)
    }
}
