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
import androidx.compose.material.icons.filled.DragIndicator
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
fun ArrangeGrid(devices: List<SavedDeviceEntity>, rooms: List<RoomEntity>, onOrderChanged: (List<Long>) -> Unit, modifier: Modifier = Modifier) {
    val items = remember(devices.map { it.id }) { devices.toMutableStateList() }
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
                        dragging = info.key as Long; start = info.offset.toOffset(); delta = Offset.Zero
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, amount ->
                    change.consume()
                    val key = dragging ?: return@detectDragGesturesAfterLongPress
                    delta += amount
                    val current = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return@detectDragGesturesAfterLongPress
                    val center = start + delta + Offset(current.size.width / 2f, current.size.height / 2f)
                    val target = state.itemAt(center)?.takeIf { it.key != key } ?: return@detectDragGesturesAfterLongPress
                    val from = items.indexOfFirst { it.id == key }
                    val to = items.indexOfFirst { it.id == target.key }
                    if (from >= 0 && to >= 0) {
                        items.add(to, items.removeAt(from))
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOrderChanged(items.map { it.id })
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
        items(items, key = { it.id }) { d ->
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
                DeviceTile(d, rooms, onClick = {}, onLongClick = null, size = TileSize.MEDIUM)
                Icon(Icons.Filled.DragIndicator, null, Modifier.align(Alignment.TopEnd).padding(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun LazyGridState.itemAt(pos: Offset) = layoutInfo.visibleItemsInfo.firstOrNull {
    pos.x >= it.offset.x && pos.x <= it.offset.x + it.size.width && pos.y >= it.offset.y && pos.y <= it.offset.y + it.size.height
}

private fun IntOffset.toOffset() = Offset(x.toFloat(), y.toFloat())
