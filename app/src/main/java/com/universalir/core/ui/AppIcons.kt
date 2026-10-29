package com.universalir.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.categoryOf
import com.universalir.ir.protocol.DeviceCategory

/**
 * Hand-drawn 24×24 line icons (SVG path data). Strokes are black and get tinted by [Icon],
 * so every icon follows the theme in light and dark mode.
 */
object AppIcons {
    private fun icon(name: String, vararg paths: String, filled: Boolean = false, stroke: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it), fill = if (filled) SolidColor(Color.Black) else null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    // Devices
    val AirConditioner by lazy {
        icon("ac", "M3.5 5h17A1.5 1.5 0 0 1 22 6.5v5a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 11.5v-5A1.5 1.5 0 0 1 3.5 5z",
            "M5 10.2h14", "M17.2 7.6h1.2", "M8 16c-.7 1-.7 2.3 0 3.4", "M12 16v3.6", "M16 16c.7 1 .7 2.3 0 3.4")
    }
    val Television by lazy {
        icon("tv", "M3.5 4.5h17A1.5 1.5 0 0 1 22 6v10a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 16V6a1.5 1.5 0 0 1 1.5-1.5z",
            "M12 17.5V21", "M8 21h8", "M6 14.5h3")
    }
    val TvBox by lazy {
        icon("tvbox", "M3.5 13h17a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 17.5v-3A1.5 1.5 0 0 1 3.5 13z",
            "M6 16h.01", "M9 16h3", "M8.5 9a5 5 0 0 1 7 0", "M5.8 6.3a9 9 0 0 1 12.4 0")
    }
    val Speaker by lazy {
        icon("speaker", "M7 2.5h10A1.5 1.5 0 0 1 18.5 4v16a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 20V4A1.5 1.5 0 0 1 7 2.5z",
            "M12 10.5a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0-7z", "M12 6.2h.01")
    }
    val Projector by lazy {
        icon("projector", "M3.5 8h17A1.5 1.5 0 0 1 22 9.5v6a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 15.5v-6A1.5 1.5 0 0 1 3.5 8z",
            "M15.5 10a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z", "M5.5 11h4", "M5.5 14h2", "M5 17v2", "M19 17v2")
    }
    val Fan by lazy {
        icon("fan", "M12 2.5a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", "M12 9.5c-1.6-1.4-1.6-3.6 0-4.5c1.6.9 1.6 3.1 0 4.5z",
            "M12 9.5c2 .6 3.1 2.5 2.3 4.1c-1.8 0-3.1-1.9-2.3-4.1z", "M12 9.5c-.4 2.1-2.4 3.2-3.9 2.3c.2-1.8 2.2-2.9 3.9-2.3z",
            "M12 16.5V21", "M8 21h8")
    }
    val Remote by lazy {
        icon("remote", "M8.5 2h7A1.5 1.5 0 0 1 17 3.5v17a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 20.5v-17A1.5 1.5 0 0 1 8.5 2z",
            "M12 5a1.5 1.5 0 1 1 0 3a1.5 1.5 0 1 1 0-3z", "M10 11.5h.01", "M14 11.5h.01", "M10 14.5h.01", "M14 14.5h.01", "M10 17.5h.01", "M14 17.5h.01")
    }
    val Bulb by lazy {
        icon("bulb", "M9 18h6", "M10 21h4", "M12 3a6 6 0 0 0-3.6 10.8c.7.6 1.1 1.3 1.1 2.2h5c0-.9.4-1.6 1.1-2.2A6 6 0 0 0 12 3z")
    }
    val Heater by lazy {
        icon("heater", "M4 6.5a1.5 1.5 0 0 1 3 0v11a1.5 1.5 0 0 1-3 0z", "M10.5 6.5a1.5 1.5 0 0 1 3 0v11a1.5 1.5 0 0 1-3 0z",
            "M17 6.5a1.5 1.5 0 0 1 3 0v11a1.5 1.5 0 0 1-3 0z", "M7 9h3.5", "M13.5 9H17", "M7 15h3.5", "M13.5 15H17", "M5.5 19v2", "M18.5 19v2")
    }
    val Console by lazy {
        icon("console", "M7 7.5h10a4.5 4.5 0 0 1 4.5 4.5v1.5a3.5 3.5 0 0 1-6.2 2.2l-.8-.9h-5l-.8.9A3.5 3.5 0 0 1 2.5 13.5V12A4.5 4.5 0 0 1 7 7.5z",
            "M7 10.5v3", "M5.5 12h3", "M15.5 11h.01", "M17.5 13h.01")
    }
    val Wifi by lazy { icon("wifi", "M2 8.5a15 15 0 0 1 20 0", "M5 12a10 10 0 0 1 14 0", "M8.5 15.5a5 5 0 0 1 7 0", "M12 19.5h.01") }

    // Remote buttons
    val Power by lazy { icon("power", "M12 3v8", "M6.7 6.7a7.5 7.5 0 1 0 10.6 0", stroke = 2.2f) }
    val VolumeUp by lazy { icon("vol_up", "M4 9.5h3l4.5-4v13l-4.5-4H4z", "M15 9a4 4 0 0 1 0 6", "M17.8 6.3a7.8 7.8 0 0 1 0 11.4") }
    val VolumeDown by lazy { icon("vol_down", "M4 9.5h3l4.5-4v13l-4.5-4H4z", "M15 9a4 4 0 0 1 0 6") }
    val Mute by lazy { icon("mute", "M4 9.5h3l4.5-4v13l-4.5-4H4z", "M16 9.5l5 5", "M21 9.5l-5 5") }
    val Plus by lazy { icon("plus", "M12 5v14", "M5 12h14", stroke = 2.2f) }
    val Minus by lazy { icon("minus", "M5 12h14", stroke = 2.2f) }
    val Up by lazy { icon("up", "M6 15l6-6 6 6", stroke = 2.2f) }
    val Down by lazy { icon("down", "M6 9l6 6 6-6", stroke = 2.2f) }
    val Left by lazy { icon("left", "M15 6l-6 6 6 6", stroke = 2.2f) }
    val Right by lazy { icon("right", "M9 6l6 6-6 6", stroke = 2.2f) }
    val Input by lazy { icon("input", "M3 12h11", "M10 8l4 4-4 4", "M14 4h5a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5") }
    val Home by lazy { icon("home", "M4 10.5L12 4l8 6.5V19a1.5 1.5 0 0 1-1.5 1.5H15v-6H9v6H5.5A1.5 1.5 0 0 1 4 19z") }
    val Back by lazy { icon("back", "M9 6.5L4.5 11 9 15.5", "M4.5 11H15a4.5 4.5 0 0 1 0 9h-2") }
    val Menu by lazy { icon("menu", "M4 7h16", "M4 12h16", "M4 17h10") }
    val Play by lazy { icon("play", "M7.5 5v14l11-7z", filled = true) }
    val Pause by lazy { icon("pause", "M8 5.5v13", "M16 5.5v13", stroke = 2.6f) }
    val Stop by lazy { icon("stop", "M6.5 6.5h11v11h-11z", filled = true) }
    val Next by lazy { icon("next", "M5.5 5.5l9 6.5-9 6.5z", "M18.5 5.5v13", filled = true) }
    val Previous by lazy { icon("prev", "M18.5 5.5l-9 6.5 9 6.5z", "M5.5 5.5v13", filled = true) }
    val FastForward by lazy { icon("ff", "M3 6.5l8 5.5-8 5.5z", "M13 6.5l8 5.5-8 5.5z", filled = true) }
    val Rewind by lazy { icon("rew", "M21 6.5l-8 5.5 8 5.5z", "M11 6.5l-8 5.5 8 5.5z", filled = true) }
    val Wind by lazy { icon("wind", "M3 8h10a3 3 0 1 0-3-3", "M3 12h15a3 3 0 1 1-3 3", "M3 16h7") }
    val Swing by lazy { icon("swing", "M20 12a8 8 0 1 1-2.3-5.7", "M20 4v4h-4") }
    val Timer by lazy { icon("timer", "M12 5a8 8 0 1 1 0 16a8 8 0 1 1 0-16z", "M12 9v4l2.5 1.5", "M10 2h4") }
    val Tune by lazy { icon("tune", "M4 7h9", "M17 7h3", "M15 5v4", "M4 17h3", "M11 17h9", "M9 15v4") }
    val Snowflake by lazy {
        icon("cool", "M12 2.5v19", "M3.8 7.2l16.4 9.6", "M20.2 7.2L3.8 16.8", "M9.6 4L12 6.2 14.4 4", "M9.6 20L12 17.8 14.4 20")
    }
    val Sun by lazy {
        icon("heat", "M12 8a4 4 0 1 1 0 8a4 4 0 1 1 0-8z", "M12 2v2", "M12 20v2", "M4.9 4.9l1.4 1.4", "M17.7 17.7l1.4 1.4",
            "M2 12h2", "M20 12h2", "M4.9 19.1l1.4-1.4", "M17.7 6.3l1.4-1.4")
    }
    val Drop by lazy { icon("dry", "M12 3c3 4 6 7 6 10.5a6 6 0 0 1-12 0C6 10 9 7 12 3z") }
    val Auto by lazy { icon("auto", "M5.5 19L12 4.5 18.5 19", "M8.2 13h7.6") }
    val ZoomIn by lazy { icon("zoom_in", "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", "M20 20l-4-4", "M11 8v6", "M8 11h6") }
    val ZoomOut by lazy { icon("zoom_out", "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", "M20 20l-4-4", "M8 11h6") }
    val Keystone by lazy { icon("keystone", "M6 5h12l2.5 14h-17z", "M12 9v6", "M9.5 11.5L12 9l2.5 2.5") }
    val Info by lazy { icon("info", "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z", "M12 11v5.5", "M12 7.8h.01") }
    val Gear by lazy {
        icon("gear", "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6z",
            "M19.4 13.5a7.6 7.6 0 0 0 0-3l2-1.6-2-3.4-2.4 1a7.5 7.5 0 0 0-2.6-1.5L14 2.5h-4l-.4 2.5A7.5 7.5 0 0 0 7 6.5l-2.4-1-2 3.4 2 1.6a7.6 7.6 0 0 0 0 3l-2 1.6 2 3.4 2.4-1a7.5 7.5 0 0 0 2.6 1.5l.4 2.5h4l.4-2.5a7.5 7.5 0 0 0 2.6-1.5l2.4 1 2-3.4z")
    }
    val Exit by lazy { icon("exit", "M14 4h4.5A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5H14", "M10 8l-4 4 4 4", "M6 12h10") }
    val Guide by lazy { icon("guide", "M3.5 5h17v14h-17z", "M3.5 9.5h17", "M9 9.5V19", "M12 13h5.5", "M12 16h4") }
    val Subtitles by lazy { icon("subtitles", "M3.5 5h17A1.5 1.5 0 0 1 22 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 17.5v-11A1.5 1.5 0 0 1 3.5 5z", "M6 13h4", "M12 13h6", "M6 16h8", "M16 16h2") }
    val Apps by lazy { icon("apps", "M5 5h4v4H5z", "M15 5h4v4h-4z", "M5 15h4v4H5z", "M15 15h4v4h-4z") }
    val Record by lazy { icon("record", "M12 6a6 6 0 1 1 0 12a6 6 0 1 1 0-12z", filled = true) }
    val Star by lazy { icon("star", "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z") }
    val Heart by lazy { icon("heart", "M12 20s-7.5-4.6-7.5-10A4.5 4.5 0 0 1 12 7.4 4.5 4.5 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z") }
    val Mouse by lazy { icon("mouse", "M12 3a5.5 5.5 0 0 1 5.5 5.5v7a5.5 5.5 0 0 1-11 0v-7A5.5 5.5 0 0 1 12 3z", "M12 3v5", "M6.5 8.5h11") }
    val Moon by lazy { icon("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z") }
    val Hash by lazy { icon("hash", "M9 4l-2 16", "M17 4l-2 16", "M4.5 9h16", "M3.5 15h16") }
    val Ok by lazy { icon("ok", "M5 12.5l4.5 4.5L19 7.5", stroke = 2.4f) }
    val Arrange by lazy { icon("arrange", "M4 4h6v6H4z", "M14 14h6v6h-6z", "M14 7h6", "M17 4v6", "M4 17h6") }
}

/** Icon choices a user can give a saved device; keys are stored in [DeviceSettings.icon]. */
val deviceIconChoices: Map<String, ImageVector> by lazy {
    linkedMapOf(
        "ac" to AppIcons.AirConditioner, "tv" to AppIcons.Television, "tvbox" to AppIcons.TvBox, "speaker" to AppIcons.Speaker,
        "projector" to AppIcons.Projector, "fan" to AppIcons.Fan, "heater" to AppIcons.Heater, "light" to AppIcons.Bulb,
        "console" to AppIcons.Console, "remote" to AppIcons.Remote,
    )
}

fun defaultIconKey(c: DeviceCategory): String = when (c) {
    DeviceCategory.AIR_CONDITIONER -> "ac"
    DeviceCategory.TELEVISION -> "tv"
    DeviceCategory.TV_BOX -> "tvbox"
    DeviceCategory.AUDIO -> "speaker"
    DeviceCategory.PROJECTOR -> "projector"
    DeviceCategory.FAN -> "fan"
    DeviceCategory.LIGHT -> "light"
    DeviceCategory.OTHER -> "remote"
}

/** Gradient pairs for device avatars; index is stored in [DeviceSettings.color]. */
val deviceGradients: List<Pair<Color, Color>> = listOf(
    Color(0xFF3B82F6) to Color(0xFF06B6D4), // ocean
    Color(0xFF7C3AED) to Color(0xFFC026D3), // violet
    Color(0xFF4F46E5) to Color(0xFF3B82F6), // indigo
    Color(0xFFEC4899) to Color(0xFFF43F5E), // rose
    Color(0xFFF59E0B) to Color(0xFFF97316), // amber
    Color(0xFF10B981) to Color(0xFF14B8A6), // teal
    Color(0xFFEF4444) to Color(0xFFF97316), // ember
    Color(0xFF475569) to Color(0xFF64748B), // slate
)

fun defaultColorIndex(c: DeviceCategory): Int = when (c) {
    DeviceCategory.AIR_CONDITIONER -> 0
    DeviceCategory.TELEVISION -> 1
    DeviceCategory.TV_BOX -> 2
    DeviceCategory.AUDIO -> 3
    DeviceCategory.PROJECTOR -> 4
    DeviceCategory.FAN -> 5
    DeviceCategory.LIGHT -> 4
    DeviceCategory.OTHER -> 7
}

fun categoryIcon(c: DeviceCategory): ImageVector = deviceIconChoices.getValue(defaultIconKey(c))

/** How a saved device is drawn: its chosen (or default) icon and gradient. */
data class DeviceLook(val icon: ImageVector, val gradient: Pair<Color, Color>, val iconKey: String, val colorIndex: Int)

fun lookOf(category: DeviceCategory, settings: DeviceSettings?): DeviceLook {
    val key = settings?.icon?.takeIf { it in deviceIconChoices } ?: defaultIconKey(category)
    val color = settings?.color?.takeIf { it in deviceGradients.indices } ?: defaultColorIndex(category)
    return DeviceLook(deviceIconChoices.getValue(key), deviceGradients[color], key, color)
}

fun SavedDeviceEntity.look(): DeviceLook = lookOf(categoryOf(category), DeviceSettings.decode(customSettings))

/** Rounded gradient badge with a white device icon. */
@Composable
fun DeviceAvatar(look: DeviceLook, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        modifier.size(size).clip(MaterialTheme.shapes.medium).background(Brush.linearGradient(listOf(look.gradient.first, look.gradient.second))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(look.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.54f))
    }
}
