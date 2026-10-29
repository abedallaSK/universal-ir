package com.universalir.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.universalir.R
import com.universalir.core.theme.UniversalIrTheme
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.DeviceAvatar
import com.universalir.core.ui.LocalAppSettings
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.look
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Picks the saved device a [DeviceRemoteWidget] controls (on placement and when reconfigured). */
@AndroidEntryPoint
class WidgetConfigActivity : AppCompatActivity() {

    @Inject lateinit var devices: DeviceRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out leaves the widget unplaced.
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        val initial = runBlocking { settingsRepository.settings.first() }
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = initial)
            val list by devices.listDevices().collectAsStateWithLifecycle(initialValue = null)
            CompositionLocalProvider(LocalAppSettings provides settings) {
                UniversalIrTheme(themeMode = settings.theme, buttonSize = settings.buttonSize) {
                    AppScaffold(stringResource(R.string.widget_config_title), onBack = { finish() }) { padding ->
                        val items = list
                        when {
                            items == null -> Unit
                            items.isEmpty() -> StatusPanel(
                                Icons.Filled.Widgets, stringResource(R.string.widget_config_empty_title), stringResource(R.string.widget_config_empty_body),
                                Modifier.padding(padding), actionLabel = stringResource(R.string.widget_open_app),
                                onAction = { startActivity(Widgets.openAppIntent(this)); finish() },
                            )
                            else -> LazyColumn(
                                Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(items, key = { it.id }) { d -> DeviceRow(d) { choose(d.id) } }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(deviceId: Long) {
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(this@WidgetConfigActivity)
            val glanceId = manager.getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { it[DeviceRemoteWidget.DEVICE_ID] = deviceId }
            DeviceRemoteWidget().update(this@WidgetConfigActivity, glanceId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}

@androidx.compose.runtime.Composable
private fun DeviceRow(d: SavedDeviceEntity, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(d.look(), size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.brandName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
