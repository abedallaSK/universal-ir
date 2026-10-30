package com.universalir.feature.remote

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.universalir.core.ui.rememberHoldRepeat
import com.universalir.domain.model.RemoteStyle
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.universalir.core.ui.AppIcons
import com.universalir.core.ui.rememberFeedback
import com.universalir.domain.model.CustomButton
import com.universalir.domain.model.KeyShape
import com.universalir.ir.format.CommandNames as N
import com.universalir.ir.protocol.DeviceCategory

/** Icons a custom key can use; keys are stored in CustomButton.icon ("" = text only). */
val buttonIcons: Map<String, ImageVector> by lazy {
    linkedMapOf(
        "power" to AppIcons.Power, "ok" to AppIcons.Ok, "up" to AppIcons.Up, "down" to AppIcons.Down, "left" to AppIcons.Left, "right" to AppIcons.Right,
        "vol_up" to AppIcons.VolumeUp, "vol_down" to AppIcons.VolumeDown, "mute" to AppIcons.Mute, "plus" to AppIcons.Plus, "minus" to AppIcons.Minus,
        "input" to AppIcons.Input, "home" to AppIcons.Home, "back" to AppIcons.Back, "menu" to AppIcons.Menu, "exit" to AppIcons.Exit,
        "info" to AppIcons.Info, "settings" to AppIcons.Gear, "guide" to AppIcons.Guide, "subtitle" to AppIcons.Subtitles, "apps" to AppIcons.Apps,
        "play" to AppIcons.Play, "pause" to AppIcons.Pause, "stop" to AppIcons.Stop, "next" to AppIcons.Next, "prev" to AppIcons.Previous,
        "ff" to AppIcons.FastForward, "rew" to AppIcons.Rewind, "record" to AppIcons.Record,
        "fan" to AppIcons.Wind, "swing" to AppIcons.Swing, "timer" to AppIcons.Timer, "mode" to AppIcons.Tune, "light" to AppIcons.Bulb,
        "cool" to AppIcons.Snowflake, "heat" to AppIcons.Sun, "dry" to AppIcons.Drop, "auto" to AppIcons.Auto, "sleep" to AppIcons.Moon,
        "zoom_in" to AppIcons.ZoomIn, "zoom_out" to AppIcons.ZoomOut, "keystone" to AppIcons.Keystone, "mouse" to AppIcons.Mouse,
        "star" to AppIcons.Star, "heart" to AppIcons.Heart, "hash" to AppIcons.Hash, "wifi" to AppIcons.Wifi,
        "tv" to AppIcons.Television, "tvbox" to AppIcons.TvBox, "ac" to AppIcons.AirConditioner, "speaker" to AppIcons.Speaker,
    )
}

/** Key colours a user can pick (index stored in CustomButton.color); null = neutral surface key. */
val keyColors: List<Color> = listOf(
    Color(0xFF2F6BFF), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF1E88E5),
    Color(0xFF8E24AA), Color(0xFFFB8C00), Color(0xFF00ACC1), Color(0xFF546E7A),
)

/** Best-guess icon for a command name, used when a key is added. Digits and unknowns get text. */
fun suggestIcon(command: String): String = when {
    command == N.POWER || command == N.POWER_ON || command == N.POWER_OFF -> "power"
    command == N.OK -> "ok"
    command.startsWith("color:") -> ""
    command.startsWith("digit_") -> ""
    command == N.CH_UP -> "up"
    command == N.CH_DOWN -> "down"
    command == N.PREV -> "prev"
    command == N.FAST_FORWARD -> "ff"
    command == N.REWIND -> "rew"
    command == N.SPEED -> "fan"
    command == N.OSCILLATE -> "swing"
    command == N.LIGHT -> "light"
    command == "brightness_up" -> "plus"
    command == "brightness_down" -> "minus"
    command in buttonIcons -> command
    else -> ""
}

/** Short text for a key without an icon: digits become "1", "color:red" becomes "Red". */
fun shortLabel(command: String, label: String): String = when {
    command.startsWith("digit_") -> command.removePrefix("digit_")
    else -> label
}

/** Places keys that have no position yet (row = -1) into the first free cells, in list order. */
fun packLayout(buttons: List<CustomButton>, columns: Int): List<CustomButton> {
    val taken = mutableSetOf<Pair<Int, Int>>()
    fun cells(b: CustomButton) = (b.row until b.row + b.h).flatMap { r -> (b.col until b.col + b.w).map { r to it } }
    fun fits(r: Int, c: Int, w: Int, h: Int) = c + w <= columns && (r until r + h).all { rr -> (c until c + w).all { (rr to it) !in taken } }
    val placed = buttons.map { b ->
        val w = b.w.coerceIn(1, columns); val h = b.h.coerceIn(1, 3)
        val ok = b.row >= 0 && b.col >= 0 && fits(b.row, b.col, w, h)
        val pos = if (ok) b.row to b.col else {
            var r = 0
            var found: Pair<Int, Int>? = null
            while (found == null) {
                for (c in 0 until columns) if (fits(r, c, w, h)) { found = r to c; break }
                r++
            }
            found
        }
        b.copy(row = pos.first, col = pos.second, w = w, h = h).also { taken += cells(it) }
    }
    return placed
}

fun rowsOf(buttons: List<CustomButton>): Int = buttons.maxOfOrNull { it.row + it.h } ?: 0

/**
 * Columns for [n] keys of width [w] in a row of [columns], centred. When the leftover space can't be
 * split evenly, the gap goes in the middle so the row stays symmetric (e.g. 4 colour keys on 5 columns).
 */
fun symmetricSlots(n: Int, w: Int, columns: Int): List<Int> {
    if (n <= 0) return emptyList()
    val free = columns - n * w
    if (free <= 0) return List(n) { it * w }
    if (free % 2 == 0) return List(n) { free / 2 + it * w }
    if (n % 2 == 0) {
        // Even count, odd gap: split the keys into two halves around a centre gap.
        val side = (free - 1) / 2
        return List(n) { i -> side + i * w + if (i >= n / 2) 1 else 0 }
    }
    return List(n) { free / 2 + it * w }
}

/** Swatches for LED "color:<name>" commands, used as exact key colours. */
val ledKeyColors: Map<String, Long> = linkedMapOf(
    "red" to 0xFFFF1744, "light_red" to 0xFFFF5252, "dark_orange" to 0xFFFF6D00, "orange" to 0xFFFF9100, "light_orange" to 0xFFFFAB40,
    "gold" to 0xFFFFC400, "yellow" to 0xFFFFEA00, "green" to 0xFF00E676, "light_green" to 0xFF76FF03, "teal" to 0xFF1DE9B6,
    "aqua" to 0xFF18FFFF, "cyan" to 0xFF00E5FF, "light_cyan" to 0xFF84FFFF, "dark_cyan" to 0xFF00B8D4, "ice_blue" to 0xFFB3E5FC,
    "light_ice_blue" to 0xFFE1F5FE, "light_blue" to 0xFF40C4FF, "sky_blue" to 0xFF80D8FF, "blue" to 0xFF2979FF, "dark_blue" to 0xFF304FFE,
    "indigo" to 0xFF3D5AFE, "violet" to 0xFF7C4DFF, "purple" to 0xFFD500F9, "dark_purple" to 0xFFAA00FF, "magenta" to 0xFFF50057,
    "pink" to 0xFFFF4081, "light_pink" to 0xFFFF80AB, "white" to 0xFFFFFFFF, "warm_white" to 0xFFFFE0B2, "cold_white" to 0xFFE3F2FD,
    "red_1" to 0xFFFF1744, "red_2" to 0xFFFF3D00, "red_3" to 0xFFFF6E40, "red_4" to 0xFFFF9E80,
    "green_1" to 0xFF00E676, "green_2" to 0xFF00C853, "green_3" to 0xFF1DE9B6, "green_4" to 0xFF64FFDA,
    "blue_1" to 0xFF2979FF, "blue_2" to 0xFF2962FF, "blue_3" to 0xFF448AFF, "blue_4" to 0xFF82B1FF,
)

private const val KEY_RED = 1
private const val KEY_GREEN = 2
private const val KEY_YELLOW = 3
private const val KEY_BLUE = 4
private const val KEY_ACCENT = 0

/**
 * Builds a layout section by section. Each section starts on a fresh row, so groups never
 * interleave: power row, D-pad cross, volume/channel rockers, media, colour keys, apps, keypad, rest.
 */
private class LayoutBuilder(val columns: Int, available: List<String>, val labelOf: (String) -> String) {
    val have = available.toSet()
    val out = mutableListOf<CustomButton>()
    var row = 0
    private var n = 0

    fun has(cmd: String) = cmd in have && out.none { it.commandName == cmd }
    fun first(vararg cmds: String): String? = cmds.firstOrNull { has(it) }

    fun put(cmd: String, r: Int, c: Int, w: Int = 1, h: Int = 1, color: Int? = null, shape: KeyShape = KeyShape.ROUND,
            rgb: Long? = null, showLabel: Boolean = false, icon: String = suggestIcon(cmd)) {
        if (!has(cmd) || c < 0 || c + w > columns) return
        out += CustomButton("k${n++}", labelOf(cmd), icon, cmd, r, c, w, h, color, shape, showLabel, rgb)
    }

    /** Spreads up to [columns] keys over one row, first key at the left edge, last at the right edge. */
    fun spreadRow(cmds: List<String>, style: (String) -> Triple<Int?, KeyShape, Boolean> = { Triple(null, KeyShape.ROUND, false) }) {
        val keys = cmds.filter { has(it) }.distinct().take(columns)
        if (keys.isEmpty()) return
        val slots = when {
            keys.size == 1 -> listOf(0)
            keys.size == columns -> keys.indices.toList()
            else -> listOf(0) + symmetricSlots(keys.size - 2, 1, columns - 2).map { it + 1 } + listOf(columns - 1)
        }
        keys.zip(slots).forEach { (cmd, c) -> val (color, shape, label) = style(cmd); put(cmd, row, c, color = color, shape = shape, showLabel = label) }
        row++
    }

    /** Fills whole rows left to right with keys of width [w]. */
    fun flow(cmds: List<String>, w: Int = 1, shape: KeyShape = KeyShape.ROUND, showLabel: Boolean = false, color: (String) -> Int? = { null },
             rgb: (String) -> Long? = { null }) {
        val keys = cmds.filter { has(it) }.distinct()
        if (keys.isEmpty()) return
        val width = w.coerceAtMost(columns)
        val perRow = (columns / width).coerceAtLeast(1)
        keys.chunked(perRow).forEach { line ->
            val slots = symmetricSlots(line.size, width, columns)
            line.forEachIndexed { i, cmd -> put(cmd, row, slots[i], width, 1, color(cmd), shape, rgb(cmd), showLabel) }
            row++
        }
    }

    /**
     * Power on the left (on/off side by side when the set has both), mute on the right edge, input and
     * menu in between. Without a D-pad, mute moves down between the volume and channel rockers instead.
     */
    fun power(hasDpad: Boolean) {
        var c = 0
        if (has(N.POWER_ON) && has(N.POWER_OFF)) { put(N.POWER_ON, row, 0, color = KEY_GREEN); put(N.POWER_OFF, row, 1, color = KEY_RED); c = 2 }
        else first(N.POWER, N.POWER_ON, N.POWER_OFF)?.let { put(it, row, 0, color = if (it == N.POWER_ON) KEY_GREEN else KEY_RED); c = 1 }
        val mute = if (hasDpad || !(has(N.VOL_UP) || has(N.VOL_DOWN))) first(N.MUTE) else null
        val last = if (mute != null) columns - 1 else columns
        listOfNotNull(first(N.INPUT), first("settings", N.MENU)).forEach { cmd -> if (c < last) put(cmd, row, c++) }
        mute?.let { put(it, row, columns - 1) }
        if (out.any { it.row == row }) row++
    }

    /** D-pad as a 3×3 cross with navigation keys in the corners; volume/channel take spare side columns. */
    fun dpad() {
        val cross = listOf(N.UP, N.DOWN, N.LEFT, N.RIGHT, N.OK)
        if (cross.count { has(it) } < 3) return
        val o = (columns - 3) / 2
        val top = row
        put(N.UP, top, o + 1); put(N.LEFT, top + 1, o); put(N.OK, top + 1, o + 1, color = KEY_ACCENT); put(N.RIGHT, top + 1, o + 2); put(N.DOWN, top + 2, o + 1)
        // Spare columns beside the cross: volume on the left side, channel on the right.
        val left = o - 1
        val right = o + 3
        if (left >= 0) rocker(N.VOL_UP, N.VOL_DOWN, top, left)
        if (right < columns) {
            when {
                has(N.CH_UP) || has(N.CH_DOWN) -> rocker(N.CH_UP, N.CH_DOWN, top, right)
                left < 0 -> rocker(N.VOL_UP, N.VOL_DOWN, top, right)
                // No channel keys: Back and Home fill the right side instead of leaving a hole.
                else -> { first("back")?.let { put(it, top, right) }; first("home")?.let { put(it, top + 2, right) } }
            }
        }
        row = top + 3
        // Remaining navigation keys in one row under the pad.
        flow(listOf("back", "home", "menu", "exit", "settings", "info", "guide").filter { has(it) }.take(columns))
    }

    /** Up key, middle label key (mute for volume), down key, stacked in one column as pills. */
    private fun rocker(up: String, down: String, top: Int, col: Int) {
        if (!has(up) && !has(down)) return
        put(up, top, col, shape = KeyShape.PILL)
        if (up == N.VOL_UP) put(N.MUTE, top + 1, col)
        put(down, top + 2, col, shape = KeyShape.PILL)
    }

    /** Volume and channel rockers side by side for remotes that had no room next to the D-pad. */
    fun rockers() {
        val pairs = listOf(N.VOL_UP to N.VOL_DOWN, N.CH_UP to N.CH_DOWN).filter { (a, b) -> has(a) || has(b) }
        if (pairs.isEmpty()) return
        val cols = if (pairs.size == 2) listOf(0, columns - 1) else listOf(columns / 2)
        pairs.zip(cols).forEach { (p, c) -> put(p.first, row, c, shape = KeyShape.PILL); put(p.second, row + 1, c, shape = KeyShape.PILL) }
        // Mute in the centre between the rockers, quick channel keys beside it.
        val mid = columns / 2
        put(N.MUTE, row, mid)
        listOf("last", "list", "guide", "info").filter { has(it) }.take(3).zip(listOf(row + 1 to mid, row to mid - 1, row to mid + 1))
            .forEach { (cmd, pos) -> if (pos.second in 1 until columns - 1) put(cmd, pos.first, pos.second) }
        row += 2
    }

    fun media() {
        flow(listOf(N.PREV, N.REWIND, N.PLAY, N.FAST_FORWARD, N.NEXT))
        flow(listOf(N.PAUSE, N.STOP, "record"))
    }

    fun colourKeys() {
        val keys = listOf("red" to KEY_RED, "green" to KEY_GREEN, "yellow" to KEY_YELLOW, "blue" to KEY_BLUE).filter { has(it.first) }
        if (keys.isEmpty()) return
        flow(keys.map { it.first }, shape = KeyShape.PILL, color = { c -> keys.first { it.first == c }.second })
    }

    fun apps() = flow(listOf("youtube", "netflix", "apps", "mouse", "subtitle", "audio", "info", "guide", "exit", "settings"),
        w = if (columns >= 4) 2 else 1, shape = KeyShape.PILL, showLabel = true)

    /** Phone-style keypad centred in three columns; "last"/"list" flank the 0. */
    fun keypad() {
        if ((0..9).none { has("digit_$it") }) return
        val o = (columns - 3) / 2
        (1..9).forEach { d -> put("digit_$d", row + (d - 1) / 3, o + (d - 1) % 3, shape = KeyShape.SQUARE) }
        put("last", row + 3, o, shape = KeyShape.SQUARE, showLabel = true)
        put("digit_0", row + 3, o + 1, shape = KeyShape.SQUARE)
        put("list", row + 3, o + 2, shape = KeyShape.SQUARE, showLabel = true)
        row += 4
    }

    fun light() {
        spreadRow(listOfNotNull(first("power_on", N.POWER), "brightness_down".takeIf { has(it) }, "brightness_up".takeIf { has(it) }, "power_off".takeIf { has(it) })) { cmd ->
            when (cmd) {
                "power_on" -> Triple(KEY_GREEN, KeyShape.ROUND, false)
                "power_off", N.POWER -> Triple(KEY_RED, KeyShape.ROUND, false)
                else -> Triple(null, KeyShape.ROUND, false)
            }
        }
        flow(listOf(N.PLAY, N.MODE))
        val colours = have.filter { it.startsWith("color:") }.sortedBy { c -> ledKeyColors.keys.indexOf(c.removePrefix("color:")).let { if (it < 0) 999 else it } }
        flow(colours, rgb = { ledKeyColors[it.removePrefix("color:")] ?: 0xFF9E9E9E })
        flow(have.filter { it.startsWith("effect:") }.sorted() + listOf("speed_down", "speed_up"), w = if (columns >= 4) 2 else 1, shape = KeyShape.PILL, showLabel = true)
    }

    fun fan() {
        power(hasDpad = true)
        flow(listOf(N.SPEED, N.OSCILLATE, N.TIMER, N.MODE, N.LIGHT), showLabel = true)
        flow(have.filter { it.startsWith("speed:") || it.startsWith("reverse:") }.sorted(), w = if (columns >= 4) 2 else 1, shape = KeyShape.PILL, showLabel = true)
    }

    /** Whatever is left: text keys sorted by label, two cells wide when there is room. */
    fun rest() = flow(have.filter { has(it) }.sortedBy { labelOf(it).lowercase() }, w = if (columns >= 4) 2 else 1, shape = KeyShape.PILL, showLabel = true)
}

/**
 * A smart starting layout for a code set. Sections are chosen by device type and by the commands
 * the set really has, so every command appears exactly once and nothing overlaps.
 */
/** Column count the standard remote uses for a code set. */
fun standardColumns(available: Collection<String>, category: DeviceCategory): Int {
    val isLight = category == DeviceCategory.LIGHT || available.any { it.startsWith("color:") }
    return if (isLight || category == DeviceCategory.FAN) 4 else 5
}

fun defaultLayout(
    available: List<String>, labelOf: (String) -> String, columns: Int = 4, category: DeviceCategory = DeviceCategory.OTHER,
    order: com.universalir.domain.model.LayoutOrder = com.universalir.domain.model.LayoutOrder.SMART,
): List<CustomButton> {
    val b = LayoutBuilder(columns.coerceIn(3, 5), available, labelOf)
    val isLight = category == DeviceCategory.LIGHT || available.any { it.startsWith("color:") }
    when {
        isLight -> b.light()
        category == DeviceCategory.FAN -> b.fan()
        else -> {
            val hasDpad = listOf(N.UP, N.DOWN, N.LEFT, N.RIGHT, N.OK).count { it in available } >= 3
            if (order == com.universalir.domain.model.LayoutOrder.CLASSIC) {
                // Classic TV remote: numbers first, then volume/channel, then navigation and media.
                b.power(hasDpad = false); b.keypad(); b.rockers(); b.dpad(); b.media(); b.colourKeys(); b.apps()
            } else {
                b.power(hasDpad = hasDpad); b.dpad(); b.rockers(); b.media(); b.colourKeys(); b.apps(); b.keypad()
            }
            // Projector extras get their own row before the rest.
            b.flow(listOf(N.KEYSTONE_UP, N.KEYSTONE_DOWN, N.FOCUS_IN, N.FOCUS_OUT))
        }
    }
    b.rest()
    return packLayout(b.out, b.columns)
}

fun keyShape(shape: KeyShape): Shape = when (shape) {
    KeyShape.ROUND -> CircleShape
    KeyShape.SQUARE -> RoundedCornerShape(18.dp)
    KeyShape.PILL -> RoundedCornerShape(50)
}

/** Key colour group, used by the Vivid style to tint keys by what they do. */
private fun groupColor(command: String): Color = when {
    command.startsWith("power") -> Color(0xFFE53935)
    command in setOf(N.UP, N.DOWN, N.LEFT, N.RIGHT, N.OK, "back", "home", "menu", "exit", "settings", "info", "guide") -> Color(0xFF2F6BFF)
    command in setOf(N.VOL_UP, N.VOL_DOWN, N.MUTE, N.CH_UP, N.CH_DOWN) -> Color(0xFF0EA5A0)
    command in setOf(N.PLAY, N.PAUSE, N.STOP, N.NEXT, N.PREV, N.FAST_FORWARD, N.REWIND, "record") -> Color(0xFF8E24AA)
    command.startsWith("digit_") -> Color(0xFF475569)
    else -> Color(0xFFF59E0B)
}

/** How a style draws a plain (uncoloured) key. */
private data class KeyLook(val shape: Shape, val bg: Brush?, val fg: Color, val border: Boolean, val shadow: Dp, val scale: Float)

@Composable
private fun lookFor(style: RemoteStyle, b: CustomButton, accent: Color?): KeyLook {
    val scheme = MaterialTheme.colorScheme
    val baseShape = if (b.shape == KeyShape.ROUND && b.w != b.h) RoundedCornerShape(50) else keyShape(b.shape)
    val accentFg = if (accent != null && accent.luminance() > 0.6f) Color.Black else Color.White
    return when (style) {
        RemoteStyle.CLASSIC -> KeyLook(baseShape, accent?.let { SolidColor(it) } ?: SolidColor(scheme.surfaceContainerHigh),
            if (accent != null) accentFg else scheme.onSurface, accent == null, if (accent != null) 4.dp else 0.dp, 1f)
        RemoteStyle.MODERN -> KeyLook(if (b.shape == KeyShape.PILL) RoundedCornerShape(50) else RoundedCornerShape(22.dp),
            accent?.let { SolidColor(it) } ?: SolidColor(scheme.surfaceContainerHighest), if (accent != null) accentFg else scheme.onSurface, false, 2.dp, 1f)
        RemoteStyle.MINIMAL -> KeyLook(CircleShape.takeIf { b.w == b.h } ?: RoundedCornerShape(50),
            accent?.let { SolidColor(it.copy(alpha = 0.18f)) }, accent ?: scheme.onSurface, false, 0.dp, 1f)
        RemoteStyle.COMPACT -> KeyLook(if (b.shape == KeyShape.PILL) RoundedCornerShape(50) else RoundedCornerShape(14.dp),
            accent?.let { SolidColor(it) } ?: SolidColor(scheme.surfaceContainerHigh), if (accent != null) accentFg else scheme.onSurface, true, 0.dp, 0.8f)
        RemoteStyle.VIVID -> {
            val c = accent ?: groupColor(b.commandName)
            KeyLook(if (b.shape == KeyShape.PILL) RoundedCornerShape(50) else RoundedCornerShape(24.dp),
                Brush.linearGradient(listOf(c, lerp(c, Color.White, 0.22f))), if (c.luminance() > 0.6f) Color.Black else Color.White, false, 6.dp, 1f)
        }
    }
}

/**
 * One key of a remote grid, drawn in [style]. [onPad] draws it without its own background, for keys that
 * sit on a shared backdrop (rocker pill).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridKey(
    b: CustomButton,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    style: RemoteStyle = RemoteStyle.CLASSIC,
    onPad: Boolean = false,
) {
    val feedback = rememberFeedback()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "key")
    val accent = b.rgb?.let { Color(it) } ?: b.color?.let { keyColors.getOrNull(it) }
    val look = lookFor(style, b, accent)
    val round = b.w == b.h && (style == RemoteStyle.CLASSIC && b.shape == KeyShape.ROUND || style == RemoteStyle.MINIMAL)
    // Round keys stay circular: the circle uses the smaller side of the cell span.
    val boxW = (if (round) min(width, height) else width) * look.scale
    val boxH = (if (round) min(width, height) else height) * look.scale
    val icon = buttonIcons[b.icon]
    val fg = if (onPad) MaterialTheme.colorScheme.onSurface else look.fg
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(boxW, boxH)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (onPad) Modifier else Modifier.shadow(look.shadow, look.shape, clip = false))
                .clip(look.shape)
                .then(if (!onPad && look.bg != null) Modifier.background(look.bg) else Modifier)
                .then(if (!onPad && look.border) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), look.shape) else Modifier)
                .combinedClickable(interactionSource = interaction, indication = ripple(), role = Role.Button,
                    onClick = { feedback.click(); onClick() }, onLongClick = com.universalir.core.ui.rememberHoldRepeat(interaction, onLongClick))
                .semantics { contentDescription = b.label },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(4.dp)) {
                if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(min(boxW, boxH) * 0.42f))
                if (icon == null || b.showLabel) {
                    if (icon != null) Spacer(Modifier.height(2.dp))
                    Text(shortLabel(b.commandName, b.label), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        style = if (icon == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** A volume/channel rocker found in a layout: one column, up above down, drawn as one tall pill. */
internal data class Rocker(val col: Int, val top: Int, val bottom: Int, val label: String?, val keys: Set<String>)

internal fun findRockers(buttons: List<CustomButton>, volLabel: String, chLabel: String): List<Rocker> =
    listOf(Triple(N.VOL_UP, N.VOL_DOWN, volLabel), Triple(N.CH_UP, N.CH_DOWN, chLabel)).mapNotNull { (u, d, label) ->
        val up = buttons.firstOrNull { it.commandName == u && it.w == 1 && it.h == 1 } ?: return@mapNotNull null
        val down = buttons.firstOrNull { it.commandName == d && it.w == 1 && it.h == 1 && it.col == up.col && it.row in up.row + 1..up.row + 2 } ?: return@mapNotNull null
        val between = buttons.filter { it.col == up.col && it.row in up.row + 1 until down.row }
        // Only a mute key may sit inside the rocker (it becomes its middle).
        if (between.any { it.commandName != N.MUTE || it.w != 1 || it.h != 1 }) return@mapNotNull null
        val middleFree = down.row - up.row == 2 && between.isEmpty()
        Rocker(up.col, up.row, down.row, label.takeIf { middleFree }, setOf(up.id, down.id) + between.map { it.id })
    }

/** Read-only rendering of a layout (the remote screen), with a one-piece D-pad and rocker pills. */
@Composable
fun RemoteGrid(
    buttons: List<CustomButton>, columns: Int, onSend: (String) -> Unit, onLong: (String) -> Unit,
    modifier: Modifier = Modifier, style: RemoteStyle = RemoteStyle.CLASSIC, touchpad: Boolean = false,
    highlight: Set<String> = emptySet(), onHighlightRowPx: ((Float) -> Unit)? = null,
) {
    val placed = remember(buttons, columns) { packLayout(buttons, columns) }
    val volLabel = androidx.compose.ui.res.stringResource(com.universalir.R.string.btn_volume)
    val chLabel = androidx.compose.ui.res.stringResource(com.universalir.R.string.btn_channel)
    val rockers = remember(placed, volLabel, chLabel) { findRockers(placed, volLabel, chLabel) }
    val pad = remember(placed) { dpadCentre(placed) }
    // Only the five keys forming the cross are drawn by the pad; any other key with the same action
    // (e.g. a second OK the user added elsewhere) is still drawn as a normal key.
    val padIds = remember(placed, pad) {
        pad?.let { (r, c) ->
            val cross = mapOf(N.OK to (r to c), N.UP to (r - 1 to c), N.DOWN to (r + 1 to c), N.LEFT to (r to c - 1), N.RIGHT to (r to c + 1))
            placed.filter { b -> cross[b.commandName] == (b.row to b.col) }.map { it.id }.toSet()
        } ?: emptySet()
    }
    val padKeys = setOf(N.UP, N.DOWN, N.LEFT, N.RIGHT, N.OK)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = if (style == RemoteStyle.COMPACT) 6.dp else 12.dp
        val cell = ((maxWidth - gap * (columns - 1)) / columns).coerceAtMost(if (style == RemoteStyle.COMPACT) 76.dp else 88.dp)
        val gridWidth = cell * columns + gap * (columns - 1)
        val startPad = (maxWidth - gridWidth) / 2
        val rows = rowsOf(placed)
        fun x(c: Int) = startPad + (cell + gap) * c
        fun y(r: Int) = (cell + gap) * r
        Box(Modifier.fillMaxWidth().height(cell * rows + gap * (rows - 1).coerceAtLeast(0))) {
            rockers.forEach { r ->
                val h = cell * (r.bottom - r.top + 1) + gap * (r.bottom - r.top)
                Box(Modifier.offset(x = x(r.col), y = y(r.top)).size(cell, h), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().padding(horizontal = cell * 0.04f).clip(RoundedCornerShape(50)).background(padBrush(style))
                        .then(if (style == RemoteStyle.CLASSIC || style == RemoteStyle.COMPACT) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)) else Modifier))
                    r.label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            pad?.let { (r, c) ->
                if (touchpad) TouchPad(has = { cmd -> placed.any { it.commandName == cmd } }, size = cell * 3 + gap * 2, style = style, onSend = onSend,
                    modifier = Modifier.offset(x = x(c - 1), y = y(r - 1)))
                else DPad(
                    has = { cmd -> placed.any { it.commandName == cmd } }, size = cell * 3 + gap * 2, style = style,
                    onSend = onSend, onLong = onLong, modifier = Modifier.offset(x = x(c - 1), y = y(r - 1)),
                )
            }
            val onRocker = rockers.flatMap { it.keys }.toSet()
            val density = androidx.compose.ui.platform.LocalDensity.current
            val firstNew = placed.filter { it.id in highlight }.minByOrNull { it.row }
            androidx.compose.runtime.LaunchedEffect(firstNew?.id) {
                firstNew?.let { b -> onHighlightRowPx?.invoke(with(density) { y(b.row).toPx() }) }
            }
            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "new").animateFloat(
                0.2f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(600),
                    androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
            placed.forEach { b ->
                if (b.id in padIds) return@forEach
                val w = cell * b.w + gap * (b.w - 1); val h = cell * b.h + gap * (b.h - 1)
                if (b.id in highlight) {
                    // Newly added key: a pulsing ring so it's easy to spot after saving the layout.
                    Box(Modifier.offset(x = x(b.col) - 4.dp, y = y(b.row) - 4.dp).size(w + 8.dp, h + 8.dp)
                        .graphicsLayer { alpha = pulse.value }
                        .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(26.dp)))
                }
                GridKey(
                    b, w, h,
                    onClick = { onSend(b.commandName) }, onLongClick = { onLong(b.commandName) },
                    modifier = Modifier.offset(x = x(b.col), y = y(b.row)), style = style, onPad = b.id in onRocker,
                )
            }
        }
    }
}

@Composable
internal fun padBrush(style: RemoteStyle): Brush = when (style) {
    RemoteStyle.VIVID -> Brush.verticalGradient(listOf(Color(0xFF0EA5A0).copy(alpha = 0.35f), Color(0xFF0EA5A0).copy(alpha = 0.18f)))
    RemoteStyle.MINIMAL -> SolidColor(MaterialTheme.colorScheme.surfaceContainer)
    RemoteStyle.MODERN -> SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest)
    else -> SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)
}

/**
 * One-piece directional pad: a ring with arrows in it and OK in the middle. Arrows use absolute
 * left/right so they never mirror in RTL (they are physical directions on the remote).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DPad(has: (String) -> Boolean, size: Dp, style: RemoteStyle, onSend: (String) -> Unit, onLong: (String) -> Unit, modifier: Modifier) {
    val feedback = rememberFeedback()
    val scheme = MaterialTheme.colorScheme
    val ring: Brush = when (style) {
        RemoteStyle.VIVID -> Brush.linearGradient(listOf(Color(0xFF2F6BFF), Color(0xFF06B6D4)))
        RemoteStyle.MINIMAL -> SolidColor(Color.Transparent)
        RemoteStyle.MODERN -> SolidColor(scheme.surfaceContainerHighest)
        else -> SolidColor(scheme.surfaceContainerHigh)
    }
    val arrowTint = if (style == RemoteStyle.VIVID) Color.White else scheme.onSurface
    val shape = if (style == RemoteStyle.MODERN || style == RemoteStyle.COMPACT) RoundedCornerShape(size * 0.22f) else CircleShape
    val s = if (style == RemoteStyle.COMPACT) size * 0.86f else size
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(s).shadow(if (style == RemoteStyle.VIVID) 8.dp else 0.dp, shape, clip = false).clip(shape).background(ring)
                .then(if (style != RemoteStyle.VIVID) Modifier.border(if (style == RemoteStyle.MINIMAL) 2.dp else 1.dp, scheme.outlineVariant, shape) else Modifier),
        ) {
            @Composable
            fun Arrow(cmd: String, icon: ImageVector, align: Alignment) {
                if (!has(cmd)) return
                val arrowInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier.align(align).size(s / 3).clip(CircleShape)
                        .combinedClickable(indication = ripple(), interactionSource = arrowInteraction, role = Role.Button,
                            onClick = { feedback.click(); onSend(cmd) }, onLongClick = rememberHoldRepeat(arrowInteraction) { onLong(cmd) })
                        .semantics { contentDescription = cmd },
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = arrowTint, modifier = Modifier.size(s / 3 * 0.48f)) }
            }
            Arrow(N.UP, AppIcons.Up, Alignment.TopCenter)
            Arrow(N.DOWN, AppIcons.Down, Alignment.BottomCenter)
            Arrow(N.LEFT, AppIcons.Left, AbsoluteAlignment.CenterLeft)
            Arrow(N.RIGHT, AppIcons.Right, AbsoluteAlignment.CenterRight)
            if (has(N.OK)) {
                val okInteraction = remember { MutableInteractionSource() }
                val okBg = if (style == RemoteStyle.VIVID) Color.White else MaterialTheme.colorScheme.primary
                val okFg = if (style == RemoteStyle.VIVID) Color(0xFF2F6BFF) else MaterialTheme.colorScheme.onPrimary
                Box(
                    Modifier.align(Alignment.Center).size(s * 0.36f).shadow(4.dp, CircleShape, clip = false).clip(CircleShape).background(okBg)
                        .combinedClickable(indication = ripple(), interactionSource = okInteraction, role = Role.Button,
                            onClick = { feedback.click(); onSend(N.OK) }, onLongClick = rememberHoldRepeat(okInteraction) { onLong(N.OK) })
                        .semantics { contentDescription = N.OK },
                    contentAlignment = Alignment.Center,
                ) { Text("OK", color = okFg, style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

/** Cell of the OK key when the arrows sit directly around it, or null when there is no D-pad cross. */
fun dpadCentre(buttons: List<CustomButton>): Pair<Int, Int>? {
    val ok = buttons.firstOrNull { it.commandName == N.OK && it.w == 1 && it.h == 1 } ?: return null
    fun at(cmd: String, r: Int, c: Int) = buttons.any { it.commandName == cmd && it.row == r && it.col == c }
    val arrows = listOf(at(N.UP, ok.row - 1, ok.col), at(N.DOWN, ok.row + 1, ok.col), at(N.LEFT, ok.row, ok.col - 1), at(N.RIGHT, ok.row, ok.col + 1))
    // The pad covers the whole 3×3 block, so its corners must be empty.
    val corners = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).map { (dr, dc) -> ok.row + dr to ok.col + dc }
    val cornersFree = buttons.none { b -> corners.any { (r, c) -> r in b.row until b.row + b.h && c in b.col until b.col + b.w } }
    return if (arrows.count { it } >= 3 && ok.row >= 1 && ok.col >= 1 && cornersFree) ok.row to ok.col else null
}

/**
 * Touchpad alternative to the D-pad: swipe to move (one arrow per ~36 dp, so a long swipe scrolls
 * through a list), tap to press OK. Directions are absolute, never mirrored in RTL.
 */
@Composable
private fun TouchPad(has: (String) -> Boolean, size: Dp, style: RemoteStyle, onSend: (String) -> Unit, modifier: Modifier) {
    val feedback = rememberFeedback()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
    var touch by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    val bg: Brush = if (style == RemoteStyle.VIVID) Brush.linearGradient(listOf(Color(0xFF2F6BFF), Color(0xFF06B6D4))) else SolidColor(scheme.surfaceContainerHigh)
    val ink = if (style == RemoteStyle.VIVID) Color.White else scheme.onSurfaceVariant
    fun fire(cmd: String) {
        if (!has(cmd)) return
        flash = cmd
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        onSend(cmd)
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(28.dp)).background(bg)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(28.dp))
            .pointerInput(Unit) {
                var acc = androidx.compose.ui.geometry.Offset.Zero
                detectDragGestures(
                    onDragStart = { acc = androidx.compose.ui.geometry.Offset.Zero; touch = it },
                    onDragEnd = { touch = null; flash = null },
                    onDragCancel = { touch = null; flash = null },
                    onDrag = { change, amount ->
                        change.consume(); touch = change.position; acc += amount
                        // Whichever axis moved further decides the direction; each full step sends one arrow.
                        while (kotlin.math.abs(acc.x) >= step || kotlin.math.abs(acc.y) >= step) {
                            if (kotlin.math.abs(acc.x) >= kotlin.math.abs(acc.y)) {
                                fire(if (acc.x > 0) N.RIGHT else N.LEFT); acc = acc.copy(x = acc.x - step * kotlin.math.sign(acc.x))
                            } else {
                                fire(if (acc.y > 0) N.DOWN else N.UP); acc = acc.copy(y = acc.y - step * kotlin.math.sign(acc.y))
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { feedback.click(); fire(N.OK) })
            },
        contentAlignment = Alignment.Center,
    ) {
        // Faint direction hints at the edges; the active one lights up while swiping.
        @Composable
        fun Hint(cmd: String, icon: ImageVector, align: Alignment) {
            if (!has(cmd)) return
            Icon(icon, null, tint = if (flash == cmd) scheme.primary.takeIf { style != RemoteStyle.VIVID } ?: Color.White else ink.copy(alpha = 0.45f),
                modifier = Modifier.align(align).padding(10.dp).size(22.dp))
        }
        Hint(N.UP, AppIcons.Up, Alignment.TopCenter)
        Hint(N.DOWN, AppIcons.Down, Alignment.BottomCenter)
        Hint(N.LEFT, AppIcons.Left, AbsoluteAlignment.CenterLeft)
        Hint(N.RIGHT, AppIcons.Right, AbsoluteAlignment.CenterRight)
        if (touch == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AppIcons.Mouse, null, tint = ink.copy(alpha = 0.6f), modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(androidx.compose.ui.res.stringResource(com.universalir.R.string.touchpad_hint), style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.7f),
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        }
        touch?.let { p ->
            val d = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }
            Box(Modifier.align(Alignment.TopStart).offset { androidx.compose.ui.unit.IntOffset((p.x - d / 2).toInt(), (p.y - d / 2).toInt()) }
                .size(44.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.25f)))
        }
    }
}
