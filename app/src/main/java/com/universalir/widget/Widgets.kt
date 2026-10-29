package com.universalir.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import com.universalir.MainActivity
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.SettingsRepository
import com.universalir.domain.usecase.RemoteController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Public entry points for the home-screen widgets. */
object Widgets {
    /** Long extra on the MainActivity intent: open this saved device's remote. */
    const val EXTRA_DEVICE_ID = "com.universalir.extra.DEVICE_ID"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Re-renders every placed widget. Call after devices, favorites or settings change; safe from any thread. */
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        scope.launch { refreshAllNow(app) }
    }

    suspend fun refreshAllNow(context: Context) {
        runCatching { DeviceRemoteWidget().updateAll(context) }
        runCatching { FavoritesWidget().updateAll(context) }
    }

    /** Opens the app; with [deviceId], on that device's remote (handled by MainActivity). */
    fun openAppIntent(context: Context, deviceId: Long? = null): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (deviceId != null) {
                putExtra(EXTRA_DEVICE_ID, deviceId)
                // Distinct data keeps one PendingIntent per device instead of reusing the first one's extras.
                data = Uri.parse("universalir://widget/device/$deviceId")
            }
        }

    internal suspend fun toast(context: Context, text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/** Widgets are not Hilt-injectable; they reach the singletons through this entry point. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun devices(): DeviceRepository
    fun codes(): CodeRepository
    fun remote(): RemoteController
    fun settings(): SettingsRepository
}

internal fun Context.widgetDeps(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)
