package com.universalir.feature.devices

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import com.universalir.core.ui.AppIcons
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.universalir.R
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.DeviceAvatar
import com.universalir.core.ui.categoryName
import com.universalir.core.ui.lookOf
import com.universalir.data.local.RoomEntity
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.TileSize
import com.universalir.domain.model.categoryOf

fun TileSize.span(maxLineSpan: Int): Int = if (this == TileSize.WIDE || this == TileSize.LARGE) minOf(2, maxLineSpan) else 1

fun SavedDeviceEntity.settings(): DeviceSettings = DeviceSettings.decode(customSettings) ?: DeviceSettings()

/** A saved device as a home/grid tile. Tap opens, long-press shows actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceTile(
    d: SavedDeviceEntity,
    rooms: List<RoomEntity>,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: TileSize? = null,
) {
    val haptics = LocalHapticFeedback.current
    val settings = remember(d.customSettings) { d.settings() }
    val cat = categoryOf(d.category)
    val look = lookOf(cat, settings)
    val tile = size ?: settings.tileSize
    val room = rooms.firstOrNull { it.id == d.roomId }?.name
    val subtitle = listOfNotNull(room ?: stringResource(categoryName(cat)), d.brandName.takeIf { it.isNotBlank() }).joinToString(" · ")
    val accent = look.gradient.first
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .heightIn(min = when (tile) { TileSize.SMALL -> 76.dp; TileSize.MEDIUM -> 150.dp; TileSize.WIDE -> 104.dp; TileSize.LARGE -> 190.dp })
            .clip(MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), MaterialTheme.shapes.large)
            .combinedClickable(role = Role.Button, onClick = onClick,
                onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } }),
    ) {
        // Soft accent wash in the tile corner, picked from the device colour.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(accent.copy(alpha = 0.14f), Color.Transparent), center = Offset.Zero, radius = 460f)))
        when (tile) {
            TileSize.SMALL, TileSize.WIDE -> Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(look, size = if (tile == TileSize.WIDE) 60.dp else 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.displayName, style = if (tile == TileSize.WIDE) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (d.favorite) Icon(Icons.Filled.Star, stringResource(R.string.favorite), tint = LocalStatusColors.current.warning, modifier = Modifier.size(18.dp))
            }
            TileSize.MEDIUM, TileSize.LARGE -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    DeviceAvatar(look, size = if (tile == TileSize.LARGE) 72.dp else 48.dp)
                    Spacer(Modifier.weight(1f))
                    if (d.favorite) Icon(Icons.Filled.Star, stringResource(R.string.favorite), tint = LocalStatusColors.current.warning, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(d.displayName, style = if (tile == TileSize.LARGE) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Drag-to-rearrange grid: hold a tile, then drag it over another tile to swap places.
 * The new order is reported once, when the user taps Done (handled by the caller via [order]).
 */
@Composable
fun ArrangeGrid(
    devices: List<SavedDeviceEntity>, rooms: List<RoomEntity>, onOrderChanged: (List<Long>) -> Unit, modifier: Modifier = Modifier,
    onResize: ((SavedDeviceEntity) -> Unit)? = null, onHide: ((SavedDeviceEntity) -> Unit)? = null,
    hidden: List<SavedDeviceEntity> = emptyList(), onShow: ((SavedDeviceEntity) -> Unit)? = null,
) {
    // The grid owns only the order; tiles are drawn from the live device rows so resizes show at once.
    val items = remember(devices.map { it.id }.toSet()) { devices.map { it.id }.toMutableStateList() }
    val byId = devices.associateBy { it.id }
    val state = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<Long?>(null) }
    var start by remember { mutableStateOf(Offset.Zero) }
    var delta by remember { mutableStateOf(Offset.Zero) }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), state = state,
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp).pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    state.itemAt(pos)?.let { info ->
                        dragging = info.key as? Long ?: return@let; start = info.offset.toOffset(); delta = Offset.Zero
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, amount ->
                    change.consume()
                    val key = dragging ?: return@detectDragGesturesAfterLongPress
                    delta += amount
                    val current = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return@detectDragGesturesAfterLongPress
                    val center = start + delta + Offset(current.size.width / 2f, current.size.height / 2f)
                    val target = state.itemAt(center)?.takeIf { it.key != key && it.key is Long } ?: return@detectDragGesturesAfterLongPress
                    val from = items.indexOf(key)
                    val to = items.indexOf(target.key as Long)
                    if (from >= 0 && to >= 0) {
                        items.add(to, items.removeAt(from))
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOrderChanged(items.toList())
                    }
                },
                onDragEnd = { dragging = null },
                onDragCancel = { dragging = null },
            )
        },
        contentPadding = PaddingValues(bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items.filter { it in byId }, key = { it }, span = { id -> GridItemSpan(byId.getValue(id).settings().tileSize.span(maxLineSpan)) }) { id ->
            val d = byId.getValue(id)
            val isDragged = dragging == d.id
            Box(
                (if (isDragged) Modifier else Modifier.animateItem())
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        if (isDragged) {
                            val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == d.id }
                            if (info != null) {
                                translationX = start.x + delta.x - info.offset.x
                                translationY = start.y + delta.y - info.offset.y
                            }
                            scaleX = 1.06f; scaleY = 1.06f; shadowElevation = 24f
                        }
                    },
            ) {
                DeviceTile(d, rooms, onClick = { onResize?.invoke(d) }, onLongClick = null,
                    modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (isDragged) 1f else 0.35f), RoundedCornerShape(24.dp)))
                // Edit controls: tap the size pill to cycle the four sizes, the eye to hide from home.
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onResize != null) SizePill(d.settings().tileSize) { onResize(d) }
                    if (onHide != null) EditBadge(AppIcons.EyeOff, stringResource(R.string.device_hide_home)) { onHide(d) }
                }
                Icon(AppIcons.Grip, stringResource(R.string.home_edit_drag), Modifier.align(Alignment.BottomEnd).padding(10.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (hidden.isNotEmpty() && onShow != null) {
            item(key = "hidden_header", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.EyeOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_hidden), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(hidden, key = { "h${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { d ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().animateItem()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DeviceAvatar(lookOf(categoryOf(d.category), d.settings()), size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(d.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        androidx.compose.material3.FilledTonalButton(onClick = { onShow(d) }) {
                            Icon(AppIcons.Eye, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_show))
                        }
                    }
                }
            }
        }
    }
}

/** Current tile size with the resize glyph; each tap moves to the next size. */
@Composable
private fun SizePill(size: TileSize, onClick: () -> Unit) {
    val label = stringResource(when (size) {
        TileSize.SMALL -> R.string.size_small; TileSize.MEDIUM -> R.string.size_medium
        TileSize.WIDE -> R.string.size_wide; TileSize.LARGE -> R.string.size_large
    })
    val desc = stringResource(R.string.device_tile_size) + ": " + label
    Row(
        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .combinedClickable(onClick = onClick, role = Role.Button).semantics { contentDescription = desc }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Resize, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private fun LazyGridState.itemAt(pos: Offset) = layoutInfo.visibleItemsInfo.firstOrNull {
    pos.x >= it.offset.x && pos.x <= it.offset.x + it.size.width && pos.y >= it.offset.y && pos.y <= it.offset.y + it.size.height
}

private fun IntOffset.toOffset() = Offset(x.toFloat(), y.toFloat())

@Composable
private fun EditBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
            .combinedClickable(onClick = onClick, role = Role.Button).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
}

/** Next tile size in the one-tap resize cycle. */
fun TileSize.next(): TileSize = TileSize.entries[(ordinal + 1) % TileSize.entries.size]
