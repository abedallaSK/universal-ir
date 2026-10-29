package com.universalir.feature.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balcony
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Yard
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.ConfirmDialog
import com.universalir.core.ui.DeviceAvatar
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.look
import com.universalir.data.local.RoomEntity
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.repository.DeviceRepository
import com.universalir.feature.devices.DeviceActionsHost
import com.universalir.feature.devices.DeviceActionsViewModel
import com.universalir.feature.devices.DeviceRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Room icon keys are stored in RoomEntity.icon; the first six keep their original names. */
val roomIcons: Map<String, ImageVector> = linkedMapOf(
    "living" to Icons.Filled.Chair, "bedroom" to Icons.Filled.Bed, "kitchen" to Icons.Filled.Kitchen,
    "office" to Icons.Filled.Computer, "kids" to Icons.Filled.ChildCare, "other" to Icons.Filled.MeetingRoom,
    "tv" to Icons.Filled.Tv, "dining" to Icons.Filled.Restaurant, "bath" to Icons.Filled.Bathtub,
    "gym" to Icons.Filled.FitnessCenter, "garage" to Icons.Filled.Garage, "balcony" to Icons.Filled.Balcony, "garden" to Icons.Filled.Yard,
)
val roomColors = listOf(0xFF2F6BFF, 0xFF1E9E61, 0xFFF59E0B, 0xFFEF4444, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFEC4899, 0xFF14B8A6, 0xFF64748B)

fun roomIcon(key: String): ImageVector = roomIcons[key] ?: Icons.Filled.MeetingRoom

/** Rounded gradient badge for a room, the room counterpart of DeviceAvatar. */
@Composable
fun RoomBadge(icon: String, color: Long, size: Dp = 48.dp) {
    val c = Color(color)
    Box(
        Modifier.size(size).clip(MaterialTheme.shapes.medium).background(Brush.linearGradient(listOf(c, lerp(c, Color.White, 0.28f)))),
        contentAlignment = Alignment.Center,
    ) { Icon(roomIcon(icon), null, tint = Color.White, modifier = Modifier.size(size * 0.52f)) }
}

@HiltViewModel
class RoomsViewModel @Inject constructor(private val repo: DeviceRepository) : ViewModel() {
    val rooms = repo.rooms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val devices = repo.listDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(name: String, icon: String, color: Long) = viewModelScope.launch { repo.createRoom(name, icon, color) }
    fun update(r: RoomEntity, name: String, icon: String, color: Long) =
        viewModelScope.launch { repo.updateRoom(r.copy(name = name.trim(), icon = icon, color = color)) }
    fun delete(r: RoomEntity) = viewModelScope.launch { repo.deleteRoom(r.id) }
    fun move(rooms: List<RoomEntity>, from: Int, to: Int) = viewModelScope.launch {
        if (to !in rooms.indices) return@launch
        val list = rooms.toMutableList(); list.add(to, list.removeAt(from))
        list.forEachIndexed { i, r -> repo.updateRoom(r.copy(sortOrder = i)) }
    }
}

private data class Suggestion(val label: Int, val icon: String, val color: Long)

private val suggestions = listOf(
    Suggestion(R.string.rooms_s_living, "living", 0xFF2F6BFF), Suggestion(R.string.rooms_s_bedroom, "bedroom", 0xFF8B5CF6),
    Suggestion(R.string.rooms_s_kitchen, "kitchen", 0xFFF59E0B), Suggestion(R.string.rooms_s_office, "office", 0xFF0EA5E9),
    Suggestion(R.string.rooms_s_kids, "kids", 0xFFEC4899),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoomsScreen(onOpenRoom: (Long) -> Unit, onBack: () -> Unit, vm: RoomsViewModel = hiltViewModel()) {
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RoomEntity?>(null) }
    var menuFor by remember { mutableStateOf<RoomEntity?>(null) }
    var deleting by remember { mutableStateOf<RoomEntity?>(null) }

    AppScaffold(stringResource(R.string.nav_rooms), onBack = onBack, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.rooms_create)) })
    }) { padding ->
        val list = rooms ?: return@AppScaffold
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp), Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 104.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                StatusPanel(Icons.Filled.MeetingRoom, stringResource(R.string.empty_rooms_title), stringResource(R.string.empty_rooms_body),
                    actionLabel = stringResource(R.string.rooms_create), onAction = { creating = true })
            }
            val missing = suggestions.filter { s -> list.none { it.icon == s.icon } }
            if (missing.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(stringResource(R.string.rooms_quick_add), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        missing.forEach { s ->
                            val name = stringResource(s.label)
                            SuggestionChip(onClick = { vm.create(name, s.icon, s.color) }, label = { Text(name) },
                                icon = { Icon(roomIcon(s.icon), null, Modifier.size(18.dp), tint = Color(s.color)) })
                        }
                    }
                }
            }
            items(list, key = { it.id }) { r ->
                RoomCard(r, devices.filter { it.roomId == r.id }, onClick = { onOpenRoom(r.id) }, onLongClick = { menuFor = r }, modifier = Modifier.animateItem())
            }
        }
    }
    if (creating) RoomEditorSheet(null, onSave = { n, i, c -> vm.create(n, i, c) }, onDismiss = { creating = false })
    editing?.let { r -> RoomEditorSheet(r, onSave = { n, i, c -> vm.update(r, n, i, c) }, onDismiss = { editing = null }) }
    menuFor?.let { r ->
        val list = rooms.orEmpty()
        val i = list.indexOfFirst { it.id == r.id }
        RoomActionsSheet(r, canMoveUp = i > 0, canMoveDown = i in 0 until list.lastIndex,
            onEdit = { menuFor = null; editing = r }, onMoveUp = { vm.move(list, i, i - 1); menuFor = null },
            onMoveDown = { vm.move(list, i, i + 1); menuFor = null }, onDelete = { menuFor = null; deleting = r }, onDismiss = { menuFor = null })
    }
    deleting?.let { r ->
        ConfirmDialog(stringResource(R.string.rooms_delete_title), stringResource(R.string.rooms_delete_body, r.name), stringResource(R.string.action_delete),
            { vm.delete(r) }, { deleting = null }, destructive = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomCard(r: RoomEntity, inRoom: List<SavedDeviceEntity>, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        modifier = modifier.height(150.dp).clip(MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }),
    ) {
        Column(Modifier.padding(16.dp)) {
            RoomBadge(r.icon, r.color)
            Spacer(Modifier.weight(1f))
            Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rooms_device_count, inRoom.size), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                // Overlapping mini avatars of the first devices in the room.
                Box {
                    inRoom.take(3).forEachIndexed { i, d ->
                        Box(Modifier.offset(x = (-14 * i).dp)) { DeviceAvatar(d.look(), size = 22.dp) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomActionsSheet(
    r: RoomEntity, canMoveUp: Boolean, canMoveDown: Boolean,
    onEdit: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoomBadge(r.icon, r.color, 52.dp)
                Spacer(Modifier.width(14.dp))
                Text(r.name, style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SheetRow(Icons.Filled.Edit, stringResource(R.string.rooms_edit), onClick = onEdit)
            if (canMoveUp) SheetRow(Icons.Filled.KeyboardArrowUp, stringResource(R.string.action_move_up), onClick = onMoveUp)
            if (canMoveDown) SheetRow(Icons.Filled.KeyboardArrowDown, stringResource(R.string.action_move_down), onClick = onMoveDown)
            SheetRow(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint ?: MaterialTheme.colorScheme.onSurface)
    }
}

/** Create (room == null) or edit a room: name, icon and colour with a live preview. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoomEditorSheet(room: RoomEntity?, onSave: (String, String, Long) -> Unit, onDismiss: () -> Unit) {
    val initialName = room?.name ?: ""
    var name by remember { mutableStateOf(TextFieldValue(initialName, TextRange(initialName.length))) }
    var icon by remember { mutableStateOf(room?.icon ?: "living") }
    var color by remember { mutableLongStateOf(room?.color ?: roomColors.first()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoomBadge(icon, color, 56.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(if (room == null) R.string.rooms_create else R.string.rooms_edit), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                label = { Text(stringResource(R.string.field_name)) })
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.device_icon), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                roomIcons.forEach { (key, vector) ->
                    val selected = key == icon
                    Surface(onClick = { icon = key }, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(50.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(vector, key, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.device_color), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                roomColors.forEach { c ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { color = c },
                        contentAlignment = Alignment.Center,
                    ) { if (c == color) Icon(Icons.Filled.Check, null, tint = Color.White) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { onSave(name.text.trim(), icon, color); onDismiss() }, enabled = name.text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.action_save)) }
        }
    }
}

/**
 * Room chooser used from a device's long-press menu. Rooms can be created right here, so the
 * user never hits a dead end; [onCreate] creates the room and assigns the device to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomPickerSheet(rooms: List<RoomEntity>, current: Long?, onPick: (Long?) -> Unit, onCreate: (String, String, Long) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        RoomEditorSheet(null, onSave = { n, i, c -> onCreate(n, i, c) }, onDismiss = onDismiss)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Text(stringResource(R.string.action_move_room), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            item {
                PickRow(current == null, { onPick(null) }) {
                    Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.MeetingRoom, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.rooms_none), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
            items(rooms, key = { it.id }) { r ->
                PickRow(current == r.id, { onPick(r.id) }) {
                    RoomBadge(r.icon, r.color, 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(r.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
            item {
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.rooms_create))
                }
            }
        }
    }
}

@Composable
private fun PickRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
        RadioButton(selected, onClick)
    }
}

// ------------------------------------------------------------------ Room detail

@HiltViewModel
class RoomDetailViewModel @Inject constructor(handle: SavedStateHandle, repo: DeviceRepository) : DeviceActionsViewModel(repo) {
    val roomId: Long = handle["roomId"] ?: -1
    val room = repo.rooms().map { list -> list.firstOrNull { it.id == roomId } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val rooms = repo.rooms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val devices = repo.listDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Applies the checklist: checked devices move into this room, unchecked ones that were here leave it. */
    fun setMembers(all: List<SavedDeviceEntity>, checked: Set<Long>) = viewModelScope.launch {
        all.forEach { d ->
            val inHere = d.roomId == roomId
            if (d.id in checked && !inHere) repo.assignRoom(d.id, roomId)
            if (d.id !in checked && inHere) repo.assignRoom(d.id, null)
        }
    }
    fun update(r: RoomEntity, name: String, icon: String, color: Long) =
        viewModelScope.launch { repo.updateRoom(r.copy(name = name.trim(), icon = icon, color = color)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomDetailScreen(onOpenDevice: (Long) -> Unit, onBack: () -> Unit, vm: RoomDetailViewModel = hiltViewModel()) {
    val room by vm.room.collectAsStateWithLifecycle()
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SavedDeviceEntity?>(null) }
    val r = room
    AppScaffold(r?.name ?: "", onBack = onBack, actions = {
        if (r != null) androidx.compose.material3.IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.rooms_edit)) }
    }, floatingActionButton = {
        if (!devices.isNullOrEmpty()) ExtendedFloatingActionButton(onClick = { choosing = true }, icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.rooms_add_devices)) })
    }) { padding ->
        val all = devices ?: return@AppScaffold
        if (r == null) return@AppScaffold
        val inRoom = all.filter { it.roomId == r.id }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoomBadge(r.icon, r.color, 64.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.rooms_device_count, inRoom.size), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (inRoom.isEmpty()) item {
                StatusPanel(roomIcon(r.icon), stringResource(R.string.rooms_empty_room), "", tint = Color(r.color),
                    actionLabel = if (all.isNotEmpty()) stringResource(R.string.rooms_add_devices) else null, onAction = { choosing = true })
            }
            items(inRoom, key = { it.id }) { d ->
                DeviceRow(d, rooms, onClick = { onOpenDevice(d.id) }, onLongClick = { selected = d }, modifier = Modifier.animateItem())
            }
        }
        if (choosing) MemberSheet(all, r, onApply = { vm.setMembers(all, it) }, onDismiss = { choosing = false })
    }
    if (editing && r != null) RoomEditorSheet(r, onSave = { n, i, c -> vm.update(r, n, i, c) }, onDismiss = { editing = false })
    selected?.let { d ->
        val live = devices?.firstOrNull { it.id == d.id } ?: return@let
        DeviceActionsHost(live, rooms, vm, onOpen = onOpenDevice, onArrange = null, onDismiss = { selected = null })
    }
}

/** Checklist of every device; checked = in this room. Shows where the others currently are. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberSheet(all: List<SavedDeviceEntity>, room: RoomEntity, onApply: (Set<Long>) -> Unit, onDismiss: () -> Unit) {
    var checked by remember { mutableStateOf(all.filter { it.roomId == room.id }.map { it.id }.toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.rooms_add_devices), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(all, key = { it.id }) { d ->
                    val on = d.id in checked
                    Row(Modifier.fillMaxWidth().clickable { checked = if (on) checked - d.id else checked + d.id }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        DeviceAvatar(d.look(), size = 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(d.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Checkbox(on, { checked = if (on) checked - d.id else checked + d.id })
                    }
                }
            }
            Button(onClick = { onApply(checked); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(52.dp)) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
