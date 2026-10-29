package com.universalir.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.universalir.R
import com.universalir.core.ui.AppIcons
import com.universalir.data.repository.AccentTheme
import com.universalir.domain.model.TextNormalizer
import java.util.Locale

/** App languages: (per-app locale tag, endonym string). "system" follows the phone. */
val appLanguages = listOf(
    "en" to R.string.lang_en, "ar" to R.string.lang_ar, "he" to R.string.lang_he, "fr" to R.string.lang_fr, "es" to R.string.lang_es,
    "de" to R.string.lang_de, "pt" to R.string.lang_pt, "it" to R.string.lang_it, "ru" to R.string.lang_ru, "tr" to R.string.lang_tr,
    "fa" to R.string.lang_fa, "hi" to R.string.lang_hi, "zh" to R.string.lang_zh, "in" to R.string.lang_id, "ur" to R.string.lang_ur,
)

/** The app's current language tag, normalised ("iw" → "he", "id" → "in"), or "system". */
fun currentAppLanguage(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').ifBlank { "system" }
    .let { when (it) { "iw" -> "he"; "id" -> "in"; else -> it } }

/** A language's name in the language the app is shown in now (e.g. "German" / "Allemand"). */
private fun localName(tag: String): String {
    val loc = Locale.forLanguageTag(if (tag == "in") "id" else tag)
    return loc.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

fun setAppLanguage(tag: String) {
    AppCompatDelegate.setApplicationLocales(if (tag == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
}

/**
 * Language picker: search by either name, each row shows the language in its own script plus its
 * name in the current language, the selected one is checked. Picking applies immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSheet(onDismiss: () -> Unit) {
    val current = currentAppLanguage()
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Text(stringResource(R.string.set_language), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text(stringResource(R.string.lang_search)) })
            Spacer(Modifier.height(8.dp))
            val names = appLanguages.map { (tag, res) -> Triple(tag, stringResource(res), localName(tag)) }
            val q = TextNormalizer.normalize(query)
            val shown = if (q.isEmpty()) names else names.filter { (tag, own, local) -> TextNormalizer.normalize(own + local + tag).contains(q) }
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (q.isEmpty()) item {
                    LanguageRow(stringResource(R.string.set_system), stringResource(R.string.lang_system_desc), "⚙", current == "system") {
                        setAppLanguage("system"); onDismiss()
                    }
                }
                items(shown, key = { it.first }) { (tag, own, local) ->
                    LanguageRow(own, local.takeIf { !it.equals(own, ignoreCase = true) }, tag.uppercase().take(2), current == tag) { setAppLanguage(tag); onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(title: String, subtitle: String?, badge: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center) {
                Text(badge, style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Preset accents with their swatch colour. */
val accentPresets = listOf(
    AccentTheme.BLUE to Color(0xFF2F6BFF), AccentTheme.TEAL to Color(0xFF00897B), AccentTheme.VIOLET to Color(0xFF6D4AFF),
    AccentTheme.ORANGE to Color(0xFFE8590C), AccentTheme.ROSE to Color(0xFFD6336C), AccentTheme.GREEN to Color(0xFF2B8A3E),
)

/** Accent row: presets, wallpaper colours (Android 12+) and a custom colour that opens the picker. */
@Composable
fun AccentPicker(selected: AccentTheme, custom: Long, onPick: (AccentTheme) -> Unit, onCustom: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.set_accent), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            accentPresets.forEach { (a, c) -> Swatch(Brush.linearGradient(listOf(c, c)), a == selected, null) { onPick(a) } }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                Swatch(Brush.sweepGradient(listOf(Color(0xFF2F6BFF), Color(0xFF2B8A3E), Color(0xFFE8590C), Color(0xFFD6336C), Color(0xFF2F6BFF))),
                    selected == AccentTheme.DYNAMIC, Icons.Filled.Palette) { onPick(AccentTheme.DYNAMIC) }
            }
            // Custom: shows the chosen colour once there is one, with an eyedropper to change it.
            val c = Color(custom)
            Swatch(if (selected == AccentTheme.CUSTOM) Brush.linearGradient(listOf(c, c))
                else Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)),
                selected == AccentTheme.CUSTOM, Icons.Filled.Colorize, onClick = onCustom)
        }
    }
}

@Composable
private fun Swatch(brush: Brush, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(brush)
            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
        else if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** Popular starting points for a custom accent. */
private val paletteColors = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
    0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E,
    0xFF6D4C41, 0xFF546E7A, 0xFF37474F, 0xFF212121,
)

/**
 * Custom accent picker: tap a palette colour or fine-tune with hue / saturation / brightness sliders.
 * The preview shows a button, a chip and a device tile in the chosen colour before saving.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSheet(initial: Long, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1].coerceAtLeast(0.25f)) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.35f)) }
    val color = Color.hsv(hue, sat, value)
    val onColor = if (color.luminance() > 0.5f) Color(0xFF101418) else Color.White
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.color_custom), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            // Live preview
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(color, androidx.compose.ui.graphics.lerp(color, Color.White, 0.3f)))),
                        contentAlignment = Alignment.Center) { Icon(AppIcons.Remote, null, tint = Color.White) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.action_add), color = onColor, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.lerp(color, Color.White, 0.8f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(stringResource(R.string.filter_all), color = Color(0xFF101418), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Power, null, tint = onColor)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                paletteColors.forEach { argb ->
                    val c = Color(argb)
                    Box(Modifier.size(36.dp).clip(CircleShape).background(c).clickable {
                        val f = FloatArray(3); android.graphics.Color.colorToHSV(argb.toInt(), f); hue = f[0]; sat = f[1]; value = f[2]
                    })
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.color_hue), style = MaterialTheme.typography.labelLarge)
            // Rainbow track so the hue slider shows what it does.
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(10.dp).clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.85f, 0.95f) })))
            Slider(hue, { hue = it }, valueRange = 0f..359f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
            Text(stringResource(R.string.color_saturation), style = MaterialTheme.typography.labelLarge)
            Slider(sat, { sat = it }, valueRange = 0.25f..1f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
            Text(stringResource(R.string.color_brightness), style = MaterialTheme.typography.labelLarge)
            Slider(value, { value = it }, valueRange = 0.35f..1f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onSave(color.toArgb().toLong() and 0xFFFFFFFFL); onDismiss() }, Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

/** Small live mock-up of the app in the current theme: header, chips, a tile and keys. */
@Composable
fun ThemePreview() {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = scheme.background, border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.home_title_default), style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(scheme.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.filter_all), style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer)
                }
                Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, scheme.outline, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.home_favorites), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(scheme.primary), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Television, null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cat_tv), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(12.dp))
                listOf(AppIcons.Power to scheme.primary, AppIcons.VolumeUp to scheme.surfaceContainerHigh).forEach { (icon, bg) ->
                    Box(Modifier.padding(start = 6.dp).size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = if (bg == scheme.primary) scheme.onPrimary else scheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
