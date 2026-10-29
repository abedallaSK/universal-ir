package com.universalir.domain.usecase

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.universalir.data.repository.LoadResult
import com.universalir.domain.model.DeviceSettings
import com.universalir.widget.Widgets
import com.universalir.widget.widgetDeps
import java.util.concurrent.TimeUnit

/** AC off-timer: sends the OFF state at a set time, even when the app is closed. */
object AcTimer {
    private fun name(deviceId: Long) = "ac-off-$deviceId"

    fun schedule(context: Context, deviceId: Long, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<AcOffWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AcOffWorker.DEVICE_ID to deviceId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(deviceId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, deviceId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(name(deviceId))
    }
}

class AcOffWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(DEVICE_ID, -1)
        val deps = applicationContext.widgetDeps()
        val device = deps.devices().device(id) ?: return Result.success()
        val ds = DeviceSettings.decode(device.customSettings) ?: return Result.success()
        // The timer was cancelled or moved after this work was queued.
        val due = ds.acOffAt ?: return Result.success()
        if (due > System.currentTimeMillis() + 60_000) return Result.success()
        val set = (deps.codes().load(device.codeSetId) as? LoadResult.Ok)?.set ?: return Result.failure()
        val off = (ds.acState ?: ds.lastSentAcState ?: return Result.success()).copy(power = false)
        val outcome = deps.remote().sendAc(set, off, ds.lastSentAcState)
        val sent = outcome is SendOutcome.Success
        deps.devices().updateSettings(id) {
            it.copy(acState = off, lastSentAcState = if (sent) off else it.lastSentAcState, acOffAt = null)
        }
        Widgets.refreshAll(applicationContext)
        return Result.success()
    }

    companion object { const val DEVICE_ID = "deviceId" }
}
