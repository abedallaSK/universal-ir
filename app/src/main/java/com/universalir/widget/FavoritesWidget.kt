package com.universalir.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.universalir.R
import com.universalir.data.local.FavoriteCommandEntity
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.domain.model.DeviceSettings
import com.universalir.feature.acremote.modeName
import com.universalir.ir.protocol.ac.AcState
import kotlinx.coroutines.flow.first
import kotlin.math.max

class FavoritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavoritesWidget()
}

/** Favorite buttons / AC presets as one-tap chips, plus favorite devices as avatar tiles. */
class FavoritesWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = context.widgetDeps()
        val initialSettings = deps.settings().current()
        val initialDevices = deps.devices().listDevices().first()
        val initialFavorites = deps.devices().favoriteCommands().first()
        provideContent {
            val app by deps.settings().settings.collectAsState(initialSettings)
            val devices by deps.devices().listDevices().collectAsState(initialDevices)
            val favorites by deps.devices().favoriteCommands().collectAsState(initialFavorites)
            WidgetTheme(app.theme) { FavoritesContent(devices, favorites) }
        }
    }
}

private sealed interface FavRow {
    val key: Long
    data class Devices(val devices: List<SavedDeviceEntity>, override val key: Long) : FavRow
    data class Chips(val chips: List<FavoriteCommandEntity>, override val key: Long) : FavRow
}

@Composable
private fun FavoritesContent(devices: List<SavedDeviceEntity>, favorites: List<FavoriteCommandEntity>) {
    val context = LocalContext.current
    val size = LocalSize.current
    val colors = GlanceTheme.colors
    val byId = devices.associateBy { it.id }
    val favDevices = devices.filter { it.favorite }
    val commands = favorites.filter { it.deviceId in byId }
    val openApp = actionStartActivity(Widgets.openAppIntent(context))

    WidgetContainer(padding = 10.dp) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp).clickable(openApp), verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.nav_favorites), maxLines = 1, modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Image(ImageProvider(R.drawable.widget_ic_remote), contentDescription = context.getString(R.string.widget_open_app),
                    modifier = GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(colors.onSurfaceVariant))
            }
            Spacer(GlanceModifier.height(8.dp))
            if (favDevices.isEmpty() && commands.isEmpty()) {
                CenteredMessage(context.getString(R.string.widget_fav_empty), context.getString(R.string.widget_open_app), openApp)
                return@Column
            }
            val width = size.width.value - 20
            val tilesPerRow = max(1, ((width + 8) / 72).toInt())
            val chipCols = if (width >= 300) 2 else 1
            val rows = favDevices.chunked(tilesPerRow).mapIndexed { i, r -> FavRow.Devices(r, -1L - i) } +
                commands.chunked(chipCols).map { r -> FavRow.Chips(r, r.first().id) }
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(rows, itemId = { it.key }) { row ->
                    when (row) {
                        is FavRow.Devices -> DeviceTiles(row.devices)
                        is FavRow.Chips -> ChipRow(row.chips, chipCols, byId)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceTiles(devices: List<SavedDeviceEntity>) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth()) {
            devices.forEachIndexed { i, d ->
                if (i > 0) Spacer(GlanceModifier.width(8.dp))
                val device = WidgetIcons.device(d)
                Column(GlanceModifier.width(64.dp).clickable(actionStartActivity(Widgets.openAppIntent(context, d.id))),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    AvatarBadge(device, 44.dp)
                    Spacer(GlanceModifier.height(4.dp))
                    Text(d.displayName, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp, textAlign = TextAlign.Center))
                }
            }
        }
        Spacer(GlanceModifier.height(8.dp))
    }
}

@Composable
private fun ChipRow(chips: List<FavoriteCommandEntity>, cols: Int, byId: Map<Long, SavedDeviceEntity>) {
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth()) {
            chips.forEachIndexed { i, f ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                Box(GlanceModifier.defaultWeight()) { FavoriteChip(f, byId[f.deviceId]) }
            }
            repeat(cols - chips.size) {
                Spacer(GlanceModifier.width(6.dp))
                Spacer(GlanceModifier.defaultWeight())
            }
        }
        Spacer(GlanceModifier.height(6.dp))
    }
}

@Composable
private fun FavoriteChip(f: FavoriteCommandEntity, device: SavedDeviceEntity?) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    val ac = f.acStateJson?.let { runCatching { DeviceSettings.json.decodeFromString(AcState.serializer(), it) }.getOrNull() }
    val icon = when {
        ac != null -> WidgetIcons.mode(ac.mode)
        device != null -> WidgetIcons.device(device).icon
        else -> R.drawable.widget_ic_remote
    }
    val detail = if (ac != null) {
        if (ac.power) "${context.getString(modeName(ac.mode))} ⁦${ac.temperatureCelsius}°⁩" else context.getString(R.string.ac_power_off)
    } else null
    val subtitle = listOfNotNull(device?.displayName, detail).joinToString(" · ")
    val action = actionRunCallback<FavoriteAction>(actionParametersOf(WidgetParams.FavoriteId to f.id))
    Row(GlanceModifier.fillMaxWidth().height(52.dp).roundedBackground(colors.secondaryContainer).clickable(action).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.size(32.dp).roundedBackground(colors.primary, R.drawable.widget_dot), contentAlignment = Alignment.Center) {
            Image(ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(colors.onPrimary))
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(f.label, maxLines = 1, style = TextStyle(color = colors.onSecondaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            if (subtitle.isNotEmpty()) SmallText(subtitle, 11.sp)
        }
    }
}
