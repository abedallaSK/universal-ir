package com.universalir.feature.devices

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.ConfirmDialog
import com.universalir.core.ui.DeviceAvatar
import com.universalir.core.ui.TextInputDialog
import com.universalir.core.ui.categoryName
import com.universalir.core.ui.deviceGradients
import com.universalir.core.ui.deviceIconChoices
import com.universalir.core.ui.lookOf
import com.universalir.data.local.RoomEntity
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.repository.DeviceRepository
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.TileSize
import com.universalir.domain.model.categoryOf
import kotlinx.coroutines.launch

/** Device management shared by every screen that lists saved devices (home, saved devices). */
abstract class DeviceActionsViewModel(protected val repo: DeviceRepository) : ViewModel() {
    fun rename(id: Long, name: String) = viewModelScope.launch { repo.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { repo.deleteDevice(id) }
    fun duplicate(id: Long, suffix: String) = viewModelScope.launch { repo.duplicateDevice(id, suffix) }
    fun favorite(d: SavedDeviceEntity) = viewModelScope.launch { repo.setFavorite(d.id, !d.favorite) }
    fun moveToRoom(id: Long, room: Long?) = viewModelScope.launch { repo.assignRoom(id, room) }
    fun createRoomAndAssign(id: Long, name: String, icon: String, color: Long) =
        viewModelScope.launch { repo.assignRoom(id, repo.createRoom(name, icon, color)) }
    fun reorder(ids: List<Long>) = viewModelScope.launch { repo.reorder(ids) }
    fun setShowOnHome(id: Long, show: Boolean) = viewModelScope.launch { repo.updateSettings(id) { it.copy(showOnHome = show) } }
    fun setLook(id: Long, icon: String, color: Int, size: TileSize) =
        viewModelScope.launch { repo.updateSettings(id) { it.copy(icon = icon, color = color, tileSize = size) } }
}

private enum class Step { MENU, RENAME, DELETE, ROOM, LOOK }

/**
 * Everything a long-press on a device offers: a bottom sheet of actions, then the dialog or
 * editor the user picked. [onArrange] is null where drag-to-reorder is not available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceActionsHost(
    device: SavedDeviceEntity,
    rooms: List<RoomEntity>,
    vm: DeviceActionsViewModel,
    onOpen: (Long) -> Unit,
    onArrange: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var step by remember(device.id) { mutableStateOf(Step.MENU) }
    val settings = remember(device.customSettings) { DeviceSettings.decode(device.customSettings) ?: DeviceSettings() }
    val look = lookOf(categoryOf(device.category), settings)
    val copySuffix = stringResource(R.string.devices_copy_suffix)
    when (step) {
        Step.MENU -> ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeviceAvatar(look, size = 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val room = rooms.firstOrNull { it.id == device.roomId }?.name
                        Text(listOfNotNull(stringResource(categoryName(categoryOf(device.category))), device.brandName, room).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Button(onClick = { onDismiss(); onOpen(device.id) }) { Text(stringResource(R.string.action_open)) }
                }
                Spacer(Modifier.height(8.dp))
                // One tap: tile size and colour apply immediately, the sheet stays open to try more.
                QuickLook(settings.tileSize, look.colorIndex,
                    onSize = { vm.setLook(device.id, look.iconKey, look.colorIndex, it) },
                    onColor = { vm.setLook(device.id, look.iconKey, it, settings.tileSize) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionRow(if (device.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    stringResource(if (device.favorite) R.string.action_unfavorite else R.string.action_favorite),
                    tint = if (device.favorite) LocalStatusColors.current.warning else null) { vm.favorite(device); onDismiss() }
                ActionRow(Icons.Filled.Palette, stringResource(R.string.device_look)) { step = Step.LOOK }
                ActionRow(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename)) { step = Step.RENAME }
                ActionRow(Icons.Filled.MeetingRoom, stringResource(R.string.action_move_room)) { step = Step.ROOM }
                if (onArrange != null) ActionRow(com.universalir.core.ui.AppIcons.Arrange, stringResource(R.string.home_arrange)) { onDismiss(); onArrange() }
                ActionRow(if (settings.showOnHome) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    stringResource(if (settings.showOnHome) R.string.device_hide_home else R.string.device_show_home)) { vm.setShowOnHome(device.id, !settings.showOnHome); onDismiss() }
                ActionRow(Icons.Filled.ContentCopy, stringResource(R.string.action_duplicate)) { vm.duplicate(device.id, copySuffix); onDismiss() }
                ActionRow(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error) { step = Step.DELETE }
            }
        }
        Step.RENAME -> TextInputDialog(stringResource(R.string.action_rename), device.displayName, stringResource(R.string.field_name), { vm.rename(device.id, it) }, onDismiss)
        Step.DELETE -> ConfirmDialog(stringResource(R.string.devices_delete_title), stringResource(R.string.devices_delete_body, device.displayName),
            stringResource(R.string.action_delete), { vm.delete(device.id) }, onDismiss, destructive = true)
        Step.ROOM -> com.universalir.feature.rooms.RoomPickerSheet(rooms, device.roomId, onPick = { vm.moveToRoom(device.id, it); onDismiss() },
            onCreate = { n, i, c -> vm.createRoomAndAssign(device.id, n, i, c) }, onDismiss = onDismiss)
        Step.LOOK -> AppearanceSheet(device, settings, onSave = { icon, color, size -> vm.setLook(device.id, icon, color, size); onDismiss() }, onDismiss = onDismiss)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface)
    }
}

/** Icon, colour and tile-size editor with a live preview tile. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSheet(device: SavedDeviceEntity, settings: DeviceSettings, onSave: (String, Int, TileSize) -> Unit, onDismiss: () -> Unit) {
    val initial = lookOf(categoryOf(device.category), settings)
    var icon by remember { mutableStateOf(initial.iconKey) }
    var color by remember { mutableIntStateOf(initial.colorIndex) }
    var size by remember { mutableStateOf(settings.tileSize) }
    val preview = initial.copy(icon = deviceIconChoices.getValue(icon), gradient = deviceGradients[color], iconKey = icon, colorIndex = color)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.device_look), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            // Live preview
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.width(if (size == TileSize.WIDE || size == TileSize.LARGE) 260.dp else 150.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        DeviceAvatar(preview, size = if (size == TileSize.LARGE) 64.dp else 44.dp)
                        Spacer(Modifier.height(if (size == TileSize.SMALL) 8.dp else 20.dp))
                        Text(device.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.device_icon), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                deviceIconChoices.forEach { (key, vector) ->
                    val selected = key == icon
                    Surface(onClick = { icon = key }, shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, modifier = Modifier.size(52.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(vector, key, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.device_color), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                deviceGradients.forEachIndexed { i, (a, b) ->
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b)))
                            .border(if (i == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { color = i },
                        contentAlignment = Alignment.Center,
                    ) { if (i == color) Icon(Icons.Filled.Check, null, tint = Color.White) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.device_tile_size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val sizes = listOf(TileSize.SMALL to R.string.size_small, TileSize.MEDIUM to R.string.size_medium, TileSize.WIDE to R.string.size_wide, TileSize.LARGE to R.string.size_large)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sizes.forEachIndexed { i, (v, res) ->
                    SegmentedButton(selected = size == v, onClick = { size = v }, shape = SegmentedButtonDefaults.itemShape(i, sizes.size)) { Text(stringResource(res), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { onSave(icon, color, size) }, Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.action_save)) }
        }
    }
}

/** Size chips and colour dots that apply with a single tap. */
@Composable
private fun QuickLook(size: TileSize, color: Int, onSize: (TileSize) -> Unit, onColor: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        val sizes = listOf(TileSize.SMALL to R.string.size_small, TileSize.MEDIUM to R.string.size_medium, TileSize.WIDE to R.string.size_wide, TileSize.LARGE to R.string.size_large)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            sizes.forEachIndexed { i, (v, res) ->
                SegmentedButton(size == v, { onSize(v) }, SegmentedButtonDefaults.itemShape(i, sizes.size)) { Text(stringResource(res), maxLines = 1) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            deviceGradients.forEachIndexed { i, (a, b) ->
                Box(Modifier.size(30.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b)))
                    .border(if (i == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { onColor(i) },
                    contentAlignment = Alignment.Center) { if (i == color) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
