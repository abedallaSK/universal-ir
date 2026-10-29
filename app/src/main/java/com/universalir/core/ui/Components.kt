package com.universalir.core.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalir.R
import com.universalir.core.theme.LocalStatusColors
import com.universalir.data.repository.AppSettings
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.VerificationLevel

val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            // AutoMirrored flips in RTL automatically.
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

/** Kinds of designed error/empty states (page 23). Each comes with a next action. */
enum class StateKind { NO_IR, NO_CARRIER, NO_CODES, NOT_VERIFIED, TRANSMIT_FAILED, INVALID_FILE, UNSUPPORTED_PROTOCOL, DB_UNAVAILABLE, MIGRATION_FAILED, CORRUPT_PROFILE, EMPTY_DEVICES, EMPTY_FAVORITES, EMPTY_ROOMS }

@Composable
fun StatusPanel(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onAction) { Text(actionLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun VerificationBadge(level: VerificationLevel, modifier: Modifier = Modifier) {
    val c = LocalStatusColors.current
    val color = when (level) {
        VerificationLevel.SOURCE_DISCOVERED -> c.warning
        VerificationLevel.ENCODER_IMPLEMENTED -> MaterialTheme.colorScheme.secondary
        VerificationLevel.WAVEFORM_VALIDATED -> MaterialTheme.colorScheme.primary
        VerificationLevel.HARDWARE_TESTED, VerificationLevel.FEATURE_TESTED -> c.success
    }
    Pill(stringResource(R.string.level_short, level.rank, stringResource(verificationName(level))), color, modifier)
}

@StringRes
fun verificationName(level: VerificationLevel): Int = when (level) {
    VerificationLevel.SOURCE_DISCOVERED -> R.string.level_0
    VerificationLevel.ENCODER_IMPLEMENTED -> R.string.level_1
    VerificationLevel.WAVEFORM_VALIDATED -> R.string.level_2
    VerificationLevel.HARDWARE_TESTED -> R.string.level_3
    VerificationLevel.FEATURE_TESTED -> R.string.level_4
}

@StringRes
fun verificationDescription(level: VerificationLevel): Int = when (level) {
    VerificationLevel.SOURCE_DISCOVERED -> R.string.level_0_desc
    VerificationLevel.ENCODER_IMPLEMENTED -> R.string.level_1_desc
    VerificationLevel.WAVEFORM_VALIDATED -> R.string.level_2_desc
    VerificationLevel.HARDWARE_TESTED -> R.string.level_3_desc
    VerificationLevel.FEATURE_TESTED -> R.string.level_4_desc
}

@StringRes
fun categoryName(c: DeviceCategory): Int = when (c) {
    DeviceCategory.AIR_CONDITIONER -> R.string.cat_ac
    DeviceCategory.TELEVISION -> R.string.cat_tv
    DeviceCategory.TV_BOX -> R.string.cat_tvbox
    DeviceCategory.AUDIO -> R.string.cat_audio
    DeviceCategory.PROJECTOR -> R.string.cat_projector
    DeviceCategory.FAN -> R.string.cat_fan
    DeviceCategory.LIGHT -> R.string.cat_light
    DeviceCategory.OTHER -> R.string.cat_other
}

/** Text that must never be mirrored or re-ordered by bidi (model numbers, hex, timings). */
@Composable
fun LtrText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, style = style.copy(textDirection = TextDirection.Ltr), color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Haptic + optional click sound, honouring settings. */
class Feedback(private val haptic: () -> Unit, private val tone: ToneGenerator?, private val settings: AppSettings) {
    fun click() {
        if (settings.haptics) haptic()
        if (settings.sound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
    }
}

@Composable
fun rememberFeedback(): Feedback {
    val settings = LocalAppSettings.current
    val haptics = LocalHapticFeedback.current
    val tone = remember(settings.sound) { if (settings.sound) runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 40) }.getOrNull() else null }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    return remember(settings, tone) { Feedback({ haptics.performHapticFeedback(HapticFeedbackType.LongPress) }, tone, settings) }
}

/** Large, accessible round remote button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteButton(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = com.universalir.core.theme.LocalRemoteMetrics.current.button,
    emphasized: Boolean = false,
    showLabel: Boolean = icon == null,
    onLongClick: (() -> Unit)? = null,
) {
    val feedback = rememberFeedback()
    val bg = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(if (emphasized) 6.dp else 0.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (emphasized) 0f else 0.6f), CircleShape)
                .combinedClickable(interactionSource = interaction, indication = ripple(), role = Role.Button,
                    onClick = { feedback.click(); onClick() }, onLongClick = onLongClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(com.universalir.core.theme.LocalRemoteMetrics.current.iconSize))
            else Text(label, color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        if (showLabel && icon != null) {
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    // Cursor starts at the end of the current name, and the field is focused with the keyboard up.
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus)) },
        confirmButton = { TextButton(onClick = { onConfirm(text.text); onDismiss() }, enabled = text.text.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun KeyValueRow(key: String, value: String, ltrValue: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Spacer(Modifier.width(12.dp))
        if (ltrValue) LtrText(value, Modifier.weight(0.58f)) else Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.58f))
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
}
