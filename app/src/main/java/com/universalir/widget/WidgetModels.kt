package com.universalir.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.universalir.R
import com.universalir.core.ui.lookOf
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.repository.AppSettings
import com.universalir.data.repository.LoadResult
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.domain.model.categoryOf
import com.universalir.domain.usecase.AcStateRules
import com.universalir.ir.format.CommandNames as N
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.ac.AcMode
import com.universalir.ir.protocol.ac.AcState

/** Name, icon and avatar gradient of a saved device, as widget resources. */
data class WidgetDevice(val id: Long, val name: String, @DrawableRes val icon: Int, @DrawableRes val gradient: Int)

/** A tappable remote button: command to send, its icon and accessible label, and an optional tiny caption. */
data class WidgetButton(
    val command: String,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    @StringRes val caption: Int? = null,
    val captionText: String? = null,
)

sealed interface RemoteWidgetModel {
    data object NotConfigured : RemoteWidgetModel
    data object Missing : RemoteWidgetModel
    data class Unavailable(val device: WidgetDevice) : RemoteWidgetModel

    data class Commands(
        val device: WidgetDevice,
        val buttons: List<WidgetButton>,
        /** Up, Left, OK, Right, Down when the set has a full D-pad; shown on large widgets. */
        val dpad: Boolean,
        /** Up to four `color:*` commands with their swatch colour (ARGB). */
        val colors: List<Pair<String, Long>>,
    ) : RemoteWidgetModel

    data class Ac(
        val device: WidgetDevice,
        val state: AcState,
        val canDown: Boolean,
        val canUp: Boolean,
        val temperatureIgnored: Boolean,
    ) : RemoteWidgetModel
}

object WidgetIcons {
    private val deviceIcons = mapOf(
        "ac" to R.drawable.widget_ic_ac, "tv" to R.drawable.widget_ic_tv, "tvbox" to R.drawable.widget_ic_tvbox,
        "speaker" to R.drawable.widget_ic_speaker, "projector" to R.drawable.widget_ic_projector, "fan" to R.drawable.widget_ic_fan,
        "heater" to R.drawable.widget_ic_heater, "light" to R.drawable.widget_ic_bulb, "console" to R.drawable.widget_ic_console,
        "remote" to R.drawable.widget_ic_remote,
    )
    private val gradients = listOf(
        R.drawable.widget_grad_0, R.drawable.widget_grad_1, R.drawable.widget_grad_2, R.drawable.widget_grad_3,
        R.drawable.widget_grad_4, R.drawable.widget_grad_5, R.drawable.widget_grad_6, R.drawable.widget_grad_7,
    )

    fun device(d: SavedDeviceEntity, settings: DeviceSettings? = DeviceSettings.decode(d.customSettings)): WidgetDevice {
        val look = lookOf(categoryOf(d.category), settings)
        return WidgetDevice(
            d.id, d.displayName,
            deviceIcons[look.iconKey] ?: R.drawable.widget_ic_remote,
            gradients.getOrElse(look.colorIndex) { R.drawable.widget_grad_7 },
        )
    }

    fun mode(m: AcMode): Int = when (m) {
        AcMode.AUTO -> R.drawable.widget_ic_auto
        AcMode.COOL -> R.drawable.widget_ic_cool
        AcMode.HEAT -> R.drawable.widget_ic_heat
        AcMode.DRY -> R.drawable.widget_ic_dry
        AcMode.FAN_ONLY -> R.drawable.widget_ic_wind
    }
}

/** Which buttons a widget shows for a command code set. Pure, so it is unit-tested. */
object WidgetButtons {

    private fun button(cmd: String, light: Boolean): WidgetButton? = when (cmd) {
        N.POWER -> WidgetButton(cmd, R.drawable.widget_ic_power, R.string.btn_power)
        N.POWER_ON -> if (light) WidgetButton(cmd, R.drawable.widget_ic_bulb, R.string.btn_on, caption = R.string.btn_on)
            else WidgetButton(cmd, R.drawable.widget_ic_power, R.string.btn_power_on, caption = R.string.btn_on)
        N.POWER_OFF -> if (light) WidgetButton(cmd, R.drawable.widget_ic_power, R.string.btn_off, caption = R.string.btn_off)
            else WidgetButton(cmd, R.drawable.widget_ic_power, R.string.btn_power_off, caption = R.string.btn_off)
        N.MUTE -> WidgetButton(cmd, R.drawable.widget_ic_mute, R.string.btn_mute)
        N.VOL_DOWN -> WidgetButton(cmd, R.drawable.widget_ic_vol_down, R.string.btn_vol_down)
        N.VOL_UP -> WidgetButton(cmd, R.drawable.widget_ic_vol_up, R.string.btn_vol_up)
        N.CH_DOWN -> WidgetButton(cmd, R.drawable.widget_ic_down, R.string.btn_ch_down, caption = R.string.widget_ch_short)
        N.CH_UP -> WidgetButton(cmd, R.drawable.widget_ic_up, R.string.btn_ch_up, caption = R.string.widget_ch_short)
        N.INPUT -> WidgetButton(cmd, R.drawable.widget_ic_input, R.string.btn_input)
        N.HOME -> WidgetButton(cmd, R.drawable.widget_ic_home, R.string.btn_home)
        N.BACK -> WidgetButton(cmd, R.drawable.widget_ic_back, R.string.btn_back)
        N.MENU -> WidgetButton(cmd, R.drawable.widget_ic_menu, R.string.btn_menu)
        N.PLAY -> WidgetButton(cmd, R.drawable.widget_ic_play, R.string.btn_play)
        N.PAUSE -> WidgetButton(cmd, R.drawable.widget_ic_pause, R.string.btn_pause)
        N.PREV -> WidgetButton(cmd, R.drawable.widget_ic_prev, R.string.btn_prev)
        N.NEXT -> WidgetButton(cmd, R.drawable.widget_ic_next, R.string.btn_next)
        N.SPEED -> WidgetButton(cmd, R.drawable.widget_ic_wind, R.string.btn_speed)
        N.OSCILLATE -> WidgetButton(cmd, R.drawable.widget_ic_swing, R.string.btn_oscillate)
        N.TIMER -> WidgetButton(cmd, R.drawable.widget_ic_timer, R.string.btn_timer)
        N.MODE -> WidgetButton(cmd, R.drawable.widget_ic_tune, R.string.btn_mode)
        N.LIGHT -> WidgetButton(cmd, R.drawable.widget_ic_bulb, R.string.btn_light)
        N.BRIGHTNESS_DOWN -> WidgetButton(cmd, R.drawable.widget_ic_heat, R.string.btn_bright_down, captionText = "−")
        N.BRIGHTNESS_UP -> WidgetButton(cmd, R.drawable.widget_ic_heat, R.string.btn_bright_up, captionText = "+")
        N.UP -> WidgetButton(cmd, R.drawable.widget_ic_up, R.string.btn_up)
        N.DOWN -> WidgetButton(cmd, R.drawable.widget_ic_down, R.string.btn_down)
        N.LEFT -> WidgetButton(cmd, R.drawable.widget_ic_left, R.string.btn_left)
        N.RIGHT -> WidgetButton(cmd, R.drawable.widget_ic_right, R.string.btn_right)
        else -> null
    }

    /** Priority order; pairs (Vol−/Vol+, Ch−/Ch+) stay adjacent so they line up in 2- and 4-column grids. */
    fun priority(category: DeviceCategory, light: Boolean): List<String> = when {
        light -> listOf(N.POWER, N.POWER_ON, N.POWER_OFF, N.BRIGHTNESS_DOWN, N.BRIGHTNESS_UP, N.MODE, N.PLAY)
        category == DeviceCategory.FAN -> listOf(N.POWER, N.SPEED, N.OSCILLATE, N.TIMER, N.MODE, N.LIGHT, N.POWER_ON, N.POWER_OFF)
        category == DeviceCategory.AUDIO -> listOf(N.POWER, N.MUTE, N.VOL_DOWN, N.VOL_UP, N.PREV, N.NEXT, N.PLAY, N.PAUSE, N.INPUT, N.MODE, N.POWER_ON, N.POWER_OFF)
        else -> listOf(N.POWER, N.MUTE, N.VOL_DOWN, N.VOL_UP, N.CH_DOWN, N.CH_UP, N.INPUT, N.HOME, N.BACK, N.MENU, N.PLAY, N.PAUSE, N.POWER_ON, N.POWER_OFF)
    }

    fun isLight(category: DeviceCategory, available: Set<String>) =
        category == DeviceCategory.LIGHT || available.any { it.startsWith(N.COLOR_PREFIX) }

    fun buttons(category: DeviceCategory, available: Set<String>): List<WidgetButton> {
        val light = isLight(category, available)
        val order = priority(category, light).filter { it in available }
            // Separate On/Off only matter when there is no single Power toggle (lights keep both).
            .filter { light || N.POWER !in available || (it != N.POWER_ON && it != N.POWER_OFF) }
        return order.mapNotNull { button(it, light) }
    }

    fun hasDpad(available: Set<String>) = listOf(N.UP, N.DOWN, N.LEFT, N.RIGHT, N.OK).all { it in available }

    fun dpadButton(cmd: String): WidgetButton =
        if (cmd == N.OK) WidgetButton(cmd, 0, R.string.btn_ok, caption = R.string.btn_ok) else button(cmd, false)!!

    /** First [max] colour commands in a pleasant order, with swatch colours. */
    fun colors(available: Set<String>, max: Int = 4): List<Pair<String, Long>> {
        val names = ledColors.keys.toList()
        return available.filter { it.startsWith(N.COLOR_PREFIX) }
            .sortedWith(compareBy({ names.indexOf(it.removePrefix(N.COLOR_PREFIX).lowercase()).let { i -> if (i < 0) 99 else i } }, { it }))
            .take(max)
            .map { it to ledColor(it) }
    }

    private val ledColors = linkedMapOf(
        "red" to 0xFFFF1744, "red_1" to 0xFFFF1744, "green" to 0xFF00E676, "green_1" to 0xFF00E676, "blue" to 0xFF2979FF,
        "blue_1" to 0xFF2979FF, "white" to 0xFFFFFFFF, "warm_white" to 0xFFFFE0B2, "cold_white" to 0xFFE3F2FD,
        "orange" to 0xFFFF9100, "yellow" to 0xFFFFEA00, "cyan" to 0xFF00E5FF, "purple" to 0xFFD500F9, "pink" to 0xFFFF4081,
        "light_red" to 0xFFFF5252, "red_2" to 0xFFFF3D00, "red_3" to 0xFFFF6E40, "red_4" to 0xFFFF9E80, "dark_orange" to 0xFFFF6D00,
        "light_orange" to 0xFFFFAB40, "gold" to 0xFFFFC400, "green_2" to 0xFF00C853, "green_3" to 0xFF1DE9B6, "green_4" to 0xFF64FFDA,
        "light_green" to 0xFF76FF03, "teal" to 0xFF1DE9B6, "aqua" to 0xFF18FFFF, "light_cyan" to 0xFF84FFFF, "dark_cyan" to 0xFF00B8D4,
        "ice_blue" to 0xFFB3E5FC, "light_ice_blue" to 0xFFE1F5FE, "light_blue" to 0xFF40C4FF, "sky_blue" to 0xFF80D8FF,
        "blue_2" to 0xFF2962FF, "blue_3" to 0xFF448AFF, "blue_4" to 0xFF82B1FF, "dark_blue" to 0xFF304FFE, "indigo" to 0xFF3D5AFE,
        "violet" to 0xFF7C4DFF, "dark_purple" to 0xFFAA00FF, "magenta" to 0xFFF50057, "light_pink" to 0xFFFF80AB,
    )

    /** Same swatches as the light remote screen; unknown names get a stable hue. */
    fun ledColor(cmd: String): Long = ledColors[cmd.removePrefix(N.COLOR_PREFIX).lowercase()]
        ?: android.graphics.Color.HSVToColor(floatArrayOf(((cmd.hashCode() and 0x7fffffff) % 360).toFloat(), 0.8f, 1f)).toLong() and 0xFFFFFFFFL
}

/** Builds what a device widget shows, from the database and code set. */
internal object RemoteWidgetLoader {

    suspend fun load(deps: WidgetEntryPoint, device: SavedDeviceEntity?, defaults: AppSettings): RemoteWidgetModel {
        val d = device ?: return RemoteWidgetModel.Missing
        val ds = DeviceSettings.decode(d.customSettings)
        val header = WidgetIcons.device(d, ds)
        if (ds == null) return RemoteWidgetModel.Unavailable(header)
        val set = (deps.codes().load(d.codeSetId) as? LoadResult.Ok)?.set ?: return RemoteWidgetModel.Unavailable(header)
        return when (set) {
            is LoadedCodeSet.CommandSet -> {
                val available = set.commandNames - ds.hiddenButtons
                val category = categoryOf(d.category)
                RemoteWidgetModel.Commands(
                    header, WidgetButtons.buttons(category, available), WidgetButtons.hasDpad(available),
                    if (WidgetButtons.isLight(category, available)) WidgetButtons.colors(available) else emptyList(),
                )
            }
            else -> {
                val (caps, temps) = AcStateRules.capabilitiesOf(set) ?: return RemoteWidgetModel.Unavailable(header)
                val state = AcStateRules.coerce(initialAcState(ds, defaults), caps, temps)
                RemoteWidgetModel.Ac(
                    header, state,
                    canDown = AcStateRules.stepTemperature(state, caps, temps, up = false) != null,
                    canUp = AcStateRules.stepTemperature(state, caps, temps, up = true) != null,
                    temperatureIgnored = state.mode in caps.temperatureIgnoredIn,
                )
            }
        }
    }

    /** Same starting state as the AC remote screen. */
    fun initialAcState(ds: DeviceSettings, defaults: AppSettings): AcState =
        ds.acState ?: AcState(power = false, mode = defaults.defaultAcMode, temperatureCelsius = defaults.defaultAcTemp)
}
