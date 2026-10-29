package com.universalir.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.universalir.R
import com.universalir.data.repository.TemperatureUnit
import com.universalir.feature.acremote.modeName
import com.universalir.ir.format.CommandNames
import com.universalir.ir.protocol.ac.AcMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DeviceRemoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DeviceRemoteWidget()
}

/** A small remote for one saved device, chosen in [WidgetConfigActivity]. */
class DeviceRemoteWidget : GlanceAppWidget() {

    companion object {
        /** Per-widget Glance state: the bound saved device id. */
        val DEVICE_ID = longPreferencesKey("device_id")
    }

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = context.widgetDeps()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val prefs: Preferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val initialSettings = deps.settings().current()
        val initialDeviceId = prefs[DEVICE_ID]
        val initialModel = if (initialDeviceId == null) RemoteWidgetModel.NotConfigured
        else RemoteWidgetLoader.load(deps, deps.devices().device(initialDeviceId), initialSettings)

        provideContent {
            val deviceId = currentState(DEVICE_ID)
            val app by deps.settings().settings.collectAsState(initialSettings)
            val model by produceState(if (deviceId == initialDeviceId) initialModel else RemoteWidgetModel.NotConfigured, deviceId, app) {
                if (deviceId == null) {
                    value = RemoteWidgetModel.NotConfigured
                } else {
                    deps.devices().observeDevice(deviceId).distinctUntilChanged().collect { value = RemoteWidgetLoader.load(deps, it, app) }
                }
            }
            WidgetTheme(app.theme) {
                RemoteContent(model, app.temperatureUnit, configureAction(context, appWidgetId))
            }
        }
    }

    private fun configureAction(context: Context, appWidgetId: Int): Action = actionStartActivity(
        Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .setData(Uri.parse("universalir://widget/configure/$appWidgetId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun RemoteContent(model: RemoteWidgetModel, unit: TemperatureUnit, configure: Action) {
    val context = LocalContext.current
    WidgetContainer {
        when (model) {
            RemoteWidgetModel.NotConfigured ->
                CenteredMessage(context.getString(R.string.widget_pick_device), context.getString(R.string.widget_choose), configure)
            RemoteWidgetModel.Missing ->
                CenteredMessage(context.getString(R.string.widget_device_missing), context.getString(R.string.widget_choose), configure)
            is RemoteWidgetModel.Unavailable -> Column(GlanceModifier.fillMaxSize()) {
                val open = actionStartActivity(Widgets.openAppIntent(context, model.device.id))
                DeviceHeader(model.device, open)
                CenteredMessage(context.getString(R.string.widget_unavailable), context.getString(R.string.widget_open_app), open)
            }
            is RemoteWidgetModel.Commands -> CommandsContent(model)
            is RemoteWidgetModel.Ac -> AcContent(model, unit)
        }
    }
}

private fun sendAction(deviceId: Long, command: String): Action =
    actionRunCallback<SendCommandAction>(actionParametersOf(WidgetParams.DeviceId to deviceId, WidgetParams.Command to command))

private fun acAction(deviceId: Long, op: String): Action =
    actionRunCallback<AcAction>(actionParametersOf(WidgetParams.DeviceId to deviceId, WidgetParams.AcOp to op))

private const val KEY_HEIGHT = 48
private const val KEY_GAP = 6
private const val HEADER_HEIGHT = 40

@Composable
private fun CommandsContent(model: RemoteWidgetModel.Commands) {
    val context = LocalContext.current
    val size = LocalSize.current
    val id = model.device.id
    val open = actionStartActivity(Widgets.openAppIntent(context, id))
    // Container padding (12dp each side) is already applied.
    val width = size.width.value - 24
    val height = size.height.value - 24 - HEADER_HEIGHT
    val fit = max(2, (width / 56).toInt())
    val cols = min(6, fit - fit % 2)
    var rows = max(1, ((height + KEY_GAP) / (KEY_HEIGHT + KEY_GAP)).toInt())
    val showColors = model.colors.isNotEmpty() && rows >= 2
    if (showColors) rows -= 1
    val showDpad = model.dpad && rows >= 5 && width >= 180
    val gridRows = if (showDpad) rows - 3 else rows
    val buttons = model.buttons.take(cols * gridRows)

    Column(GlanceModifier.fillMaxSize()) {
        DeviceHeader(model.device, open)
        Spacer(GlanceModifier.height(10.dp))
        if (buttons.isEmpty() && !showColors && !showDpad) {
            CenteredMessage(context.getString(R.string.widget_no_buttons), context.getString(R.string.widget_open_app), open)
            return@Column
        }
        buttons.chunked(cols).forEach { row ->
            Row(GlanceModifier.fillMaxWidth()) {
                row.forEachIndexed { i, b ->
                    if (i > 0) Spacer(GlanceModifier.width(KEY_GAP.dp))
                    KeyTile(b.icon, context.getString(b.label), sendAction(id, b.command), GlanceModifier.defaultWeight(),
                        caption = b.caption?.let { context.getString(it) } ?: b.captionText,
                        emphasized = b.command == CommandNames.POWER || b.command == CommandNames.POWER_ON)
                }
                // Keep cell widths equal on a short last row.
                repeat(cols - row.size) {
                    Spacer(GlanceModifier.width(KEY_GAP.dp))
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
            Spacer(GlanceModifier.height(KEY_GAP.dp))
        }
        if (showColors) ColorRow(id, model.colors)
        if (showDpad) Dpad(id)
    }
}

@Composable
private fun ColorRow(deviceId: Long, colors: List<Pair<String, Long>>) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth().height(KEY_HEIGHT.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally) {
        colors.forEachIndexed { i, (cmd, argb) ->
            if (i > 0) Spacer(GlanceModifier.width(14.dp))
            Box(GlanceModifier.size(40.dp).roundedBackground(GlanceTheme.colors.outline, R.drawable.widget_dot), contentAlignment = Alignment.Center) {
                Image(
                    ImageProvider(R.drawable.widget_dot),
                    contentDescription = context.getString(R.string.widget_color_desc, cmd.removePrefix(CommandNames.COLOR_PREFIX).replace('_', ' ')),
                    modifier = GlanceModifier.size(36.dp).clickable(sendAction(deviceId, cmd)),
                    colorFilter = ColorFilter.tint(ColorProvider(androidx.compose.ui.graphics.Color(argb))),
                )
            }
        }
    }
}

@Composable
private fun Dpad(deviceId: Long) {
    val context = LocalContext.current
    // D-pad left/right are physical directions: never mirrored in RTL (Glance rows follow the host's direction).
    val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val middle = listOf(CommandNames.LEFT, CommandNames.OK, CommandNames.RIGHT).let { if (rtl) it.reversed() else it }
    val rowsOf = listOf(listOf<String?>(null, CommandNames.UP, null), middle, listOf<String?>(null, CommandNames.DOWN, null))
    Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        rowsOf.forEach { row ->
            Row(GlanceModifier.width(196.dp)) {
                row.forEachIndexed { i, cmd ->
                    if (i > 0) Spacer(GlanceModifier.width(KEY_GAP.dp))
                    if (cmd == null) Spacer(GlanceModifier.defaultWeight())
                    else {
                        val b = WidgetButtons.dpadButton(cmd)
                        KeyTile(b.icon, context.getString(b.label), sendAction(deviceId, cmd), GlanceModifier.defaultWeight(),
                            caption = b.caption?.let { context.getString(it) }, emphasized = cmd == CommandNames.OK, height = 44.dp)
                    }
                }
            }
            Spacer(GlanceModifier.height(KEY_GAP.dp))
        }
    }
}

@Composable
private fun AcContent(model: RemoteWidgetModel.Ac, unit: TemperatureUnit) {
    val context = LocalContext.current
    val size = LocalSize.current
    val s = model.state
    val id = model.device.id
    val open = actionStartActivity(Widgets.openAppIntent(context, id))
    val colors = GlanceTheme.colors
    val modeColor = when {
        !s.power -> colors.onSurfaceVariant
        s.mode == AcMode.HEAT -> WidgetColors.heat()
        s.mode == AcMode.COOL -> WidgetColors.cool()
        else -> colors.primary
    }
    val shown = if (unit == TemperatureUnit.FAHRENHEIT) (s.temperatureCelsius * 9 / 5f + 32).roundToInt() else s.temperatureCelsius
    val unitLabel = if (unit == TemperatureUnit.FAHRENHEIT) "°F" else "°C"
    // Isolate "24°C" as one left-to-right token so RTL locales keep the order.
    val tempText = "⁦" + (if (model.temperatureIgnored) "--" else shown.toString()) + unitLabel + "⁩"
    val status = context.getString(modeName(s.mode)) + " · " +
        context.getString(if (s.power) R.string.ac_power_on else R.string.ac_power_off)
    val compact = size.height.value < 170

    Column(GlanceModifier.fillMaxSize()) {
        if (compact) {
            DeviceHeader(model.device, open, avatar = 28.dp) {
                Text(tempText, maxLines = 1, style = TextStyle(color = modeColor, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(2.dp))
            SmallText(status)
        } else {
            DeviceHeader(model.device, open)
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(WidgetIcons.mode(s.mode)), contentDescription = null, modifier = GlanceModifier.size(24.dp),
                    colorFilter = ColorFilter.tint(modeColor))
                Spacer(GlanceModifier.width(6.dp))
                Text(tempText, maxLines = 1, style = TextStyle(color = modeColor, fontSize = 36.sp, fontWeight = FontWeight.Bold))
            }
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { SmallText(status, 13.sp) }
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth()) {
            KeyTile(R.drawable.widget_ic_minus, context.getString(R.string.ac_temp_down),
                if (model.canDown) acAction(id, WidgetParams.AC_DOWN) else null, GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(KEY_GAP.dp))
            KeyTile(R.drawable.widget_ic_power, context.getString(R.string.ac_power), acAction(id, WidgetParams.AC_POWER),
                GlanceModifier.defaultWeight(), emphasized = s.power)
            Spacer(GlanceModifier.width(KEY_GAP.dp))
            KeyTile(R.drawable.widget_ic_plus, context.getString(R.string.ac_temp_up),
                if (model.canUp) acAction(id, WidgetParams.AC_UP) else null, GlanceModifier.defaultWeight())
        }
    }
}
