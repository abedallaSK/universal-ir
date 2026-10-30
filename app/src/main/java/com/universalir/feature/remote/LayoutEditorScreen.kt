package com.universalir.feature.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.LoadingBox
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.describeOutcome
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.LoadResult
import com.universalir.domain.model.CustomButton
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.KeyShape
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.domain.model.TextNormalizer
import com.universalir.domain.usecase.RemoteController
import com.universalir.domain.usecase.SendOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

data class LayoutCommand(val name: String, val label: String)

data class LayoutEditorState(
    val loading: Boolean = true,
    val deviceName: String = "",
    val commands: List<LayoutCommand> = emptyList(),
    val buttons: List<CustomButton> = emptyList(),
    val columns: Int = 4,
    val outcome: SendOutcome? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val profiles: List<com.universalir.domain.model.LayoutProfile> = emptyList(),
    /** The remote's own button style (null = app default), so the editor looks like the remote. */
    val style: com.universalir.domain.model.RemoteStyle? = null,
)

@HiltViewModel
class LayoutEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val devices: DeviceRepository,
    private val codes: CodeRepository,
    private val remote: RemoteController,
) : ViewModel() {
    private val deviceId: Long = handle["deviceId"] ?: -1
    private val _state = MutableStateFlow(LayoutEditorState())
    val state = _state.asStateFlow()
    private var set: LoadedCodeSet.CommandSet? = null
    private var category = com.universalir.ir.protocol.DeviceCategory.OTHER
    private var order = com.universalir.domain.model.LayoutOrder.SMART

    init {
        viewModelScope.launch {
            val d = devices.device(deviceId) ?: return@launch
            val ds = DeviceSettings.decode(d.customSettings) ?: DeviceSettings()
            val cs = (codes.load(d.codeSetId) as? LoadResult.Ok)?.set as? LoadedCodeSet.CommandSet
            set = cs
            category = com.universalir.domain.model.categoryOf(d.category)
            order = ds.layoutOrder
            val commands = cs?.codes?.commands.orEmpty().map { LayoutCommand(it.name, it.label) }
            val columns = if (ds.customButtons.isEmpty()) standardColumns(commands.map { it.name }, category) else ds.layoutColumns.coerceIn(3, 5)
            val names = commands.map { it.name }
            val labelOf: (String) -> String = { n -> commands.firstOrNull { it.name == n }?.label ?: n }
            // First visit: start from the standard layout so the user edits instead of building from nothing.
            val buttons = if (ds.customButtons.isEmpty()) defaultLayout(names.filter { it !in ds.hiddenButtons }, labelOf, columns, category, order)
            else packLayout(ds.customButtons.filter { it.commandName in names }, columns)
            originalIds = buttons.map { it.id }.toSet()
            _state.value = LayoutEditorState(false, d.displayName, commands, buttons, columns, profiles = ds.layoutProfiles, style = ds.remoteStyle)
        }
    }

    private val undoStack = ArrayDeque<Pair<List<CustomButton>, Int>>()
    private val redoStack = ArrayDeque<Pair<List<CustomButton>, Int>>()

    /** Applies a layout change and records it for undo (only when something really changed). */
    private fun edit(change: (LayoutEditorState) -> LayoutEditorState) {
        val before = _state.value
        val after = change(before)
        if (after.buttons == before.buttons && after.columns == before.columns) return
        undoStack.addLast(before.buttons to before.columns)
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        _state.value = after.copy(canUndo = true, canRedo = false)
    }

    fun undo() {
        val (b, c) = undoStack.removeLastOrNull() ?: return
        val s = _state.value
        redoStack.addLast(s.buttons to s.columns)
        _state.value = s.copy(buttons = b, columns = c, canUndo = undoStack.isNotEmpty(), canRedo = true)
    }

    fun redo() {
        val (b, c) = redoStack.removeLastOrNull() ?: return
        val s = _state.value
        undoStack.addLast(s.buttons to s.columns)
        _state.value = s.copy(buttons = b, columns = c, canUndo = true, canRedo = redoStack.isNotEmpty())
    }

    /** Saves the current layout as a named profile (replacing one with the same name). */
    fun saveProfile(name: String) = viewModelScope.launch {
        val s = _state.value
        val p = com.universalir.domain.model.LayoutProfile(name.trim(), s.columns, s.buttons, System.currentTimeMillis())
        val list = s.profiles.filterNot { it.name.equals(p.name, ignoreCase = true) } + p
        devices.updateSettings(deviceId) { it.copy(layoutProfiles = list) }
        _state.update { it.copy(profiles = list) }
    }

    /** Loads a profile into the editor (undoable; still needs Save to apply to the remote). */
    fun loadProfile(p: com.universalir.domain.model.LayoutProfile) = edit { s -> s.copy(buttons = packLayout(p.buttons, p.columns), columns = p.columns) }

    fun deleteProfile(p: com.universalir.domain.model.LayoutProfile) = viewModelScope.launch {
        val list = _state.value.profiles.filterNot { it.name == p.name }
        devices.updateSettings(deviceId) { it.copy(layoutProfiles = list) }
        _state.update { it.copy(profiles = list) }
    }

    /** Pushes every key at [row] or below one row down, opening an empty row. */
    /** Removes a whole row: its keys go, taller keys crossing it shrink, everything below moves up. */
    fun deleteRow(row: Int) = edit { s ->
        s.copy(buttons = s.buttons.mapNotNull { b ->
            when {
                b.row == row && b.h == 1 -> null
                b.row == row || (b.row < row && row < b.row + b.h) -> b.copy(row = minOf(b.row, row), h = b.h - 1)
                b.row > row -> b.copy(row = b.row - 1)
                else -> b
            }
        })
    }
    fun insertRow(row: Int) = edit { s -> s.copy(buttons = s.buttons.map { if (it.row >= row) it.copy(row = it.row + 1) else it }) }

    /** Removes empty rows so the layout has no gaps. */
    fun tidy() = edit { s ->
        val used = s.buttons.flatMap { b -> b.row until b.row + b.h }.toSortedSet()
        val map = used.withIndex().associate { (i, r) -> r to i }
        s.copy(buttons = s.buttons.map { it.copy(row = map.getValue(it.row)) })
    }

    fun duplicate(b: CustomButton) = edit { s ->
        s.copy(buttons = packLayout(s.buttons + b.copy(id = UUID.randomUUID().toString().take(8), row = -1, col = -1), s.columns))
    }

    private fun label(name: String) = _state.value.commands.firstOrNull { it.name == name }?.label ?: name

    private fun occupied(buttons: List<CustomButton>, except: String?): Set<Pair<Int, Int>> = buttons.filter { it.id != except }
        .flatMap { b -> (b.row until b.row + b.h).flatMap { r -> (b.col until b.col + b.w).map { r to it } } }.toSet()

    private fun fits(buttons: List<CustomButton>, except: String?, row: Int, col: Int, w: Int, h: Int, columns: Int): Boolean {
        if (row < 0 || col < 0 || col + w > columns) return false
        val taken = occupied(buttons, except)
        return (row until row + h).all { r -> (col until col + w).all { (r to it) !in taken } }
    }

    fun addAt(row: Int, col: Int, b: CustomButton) = edit { s ->
        val w = b.w.coerceAtMost(s.columns - col)
        val placed = if (fits(s.buttons, null, row, col, w, b.h, s.columns)) b.copy(row = row, col = col, w = w) else b.copy(row = -1, col = -1)
        s.copy(buttons = packLayout(s.buttons + placed.copy(id = UUID.randomUUID().toString().take(8)), s.columns))
    }

    /** Applies edits to an existing key; if its new size no longer fits where it is, it moves to the next free cell. */
    fun update(b: CustomButton) = edit { s ->
        val ok = fits(s.buttons, b.id, b.row, b.col, b.w, b.h, s.columns)
        val next = s.buttons.map { if (it.id == b.id) (if (ok) b else b.copy(row = -1, col = -1)) else it }
        s.copy(buttons = packLayout(next, s.columns))
    }

    fun remove(id: String) = edit { s -> s.copy(buttons = s.buttons.filterNot { it.id == id }) }

    /**
     * Drop target: a free spot moves the key there; dropping on another key swaps the two (any sizes).
     * If the other key can't fit in the vacated spot it moves to the next free cell instead.
     */
    fun moveTo(id: String, row: Int, col: Int) = edit { s ->
        val b = s.buttons.firstOrNull { it.id == id } ?: return@edit s
        if (b.row == row && b.col == col) return@edit s
        val other = keyAt(s.buttons, row, col, except = id)
        if (other == null) {
            return@edit if (fits(s.buttons, id, row, col, b.w, b.h, s.columns)) s.copy(buttons = s.buttons.map { if (it.id == id) it.copy(row = row, col = col) else it }) else s
        }
        val rest = s.buttons.filter { it.id != id && it.id != other.id }
        val movedB = b.copy(row = other.row, col = other.col.coerceAtMost(s.columns - b.w))
        if (!fits(rest, null, movedB.row, movedB.col, movedB.w, movedB.h, s.columns)) return@edit s
        val withB = rest + movedB
        val movedO = other.copy(row = b.row, col = b.col.coerceAtMost(s.columns - other.w)).let {
            if (fits(withB, null, it.row, it.col, it.w, it.h, s.columns)) it else it.copy(row = -1, col = -1)
        }
        val next = s.buttons.map { when (it.id) { id -> movedB; other.id -> movedO; else -> it } }
        s.copy(buttons = packLayout(next, s.columns))
    }

    private fun keyAt(buttons: List<CustomButton>, row: Int, col: Int, except: String?) =
        buttons.firstOrNull { it.id != except && row in it.row until it.row + it.h && col in it.col until it.col + it.w }

    fun setColumns(n: Int) = edit { s ->
        // Keys that would hang over the new right edge get re-packed; the rest stay put.
        val next = s.buttons.map { if (it.col + it.w > n) it.copy(row = -1, col = -1, w = it.w.coerceAtMost(n)) else it }
        s.copy(columns = n, buttons = packLayout(next, n))
    }

    fun autoLayout() = edit { s -> s.copy(buttons = defaultLayout(s.commands.map { it.name }, ::label, s.columns, category, order)) }
    fun clear() = edit { it.copy(buttons = emptyList()) }

    fun test(command: String) = viewModelScope.launch {
        val cs = set ?: return@launch
        val outcome = remote.sendCommand(cs, command)
        _state.update { it.copy(outcome = outcome) }
    }

    private var originalIds: Set<String> = emptySet()

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val s = _state.value
        val added = s.buttons.map { it.id }.toSet() - originalIds
        val placed = s.buttons.map { it.commandName }.toSet()
        devices.updateSettings(deviceId) {
            it.copy(customButtons = s.buttons, layoutColumns = s.columns, newKeyIds = added, hiddenButtons = it.hiddenButtons - placed)
        }
        onDone()
    }

    /** Back to the built-in remote for this device type. */
    fun useStandard(onDone: () -> Unit) = viewModelScope.launch {
        devices.updateSettings(deviceId) { it.copy(customButtons = emptyList()) }
        onDone()
    }
}

private data class KeyEdit(val button: CustomButton, val isNew: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutEditorScreen(onBack: () -> Unit, vm: LayoutEditorViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<KeyEdit?>(null) }
    var profilesOpen by remember { mutableStateOf(false) }
    LaunchedEffect(s.outcome) { val o = s.outcome; if (o != null && o !is SendOutcome.Success) snackbar.showSnackbar(describeOutcome(context, o)) }

    AppScaffold(stringResource(R.string.layout_title), onBack = onBack, snackbar = snackbar, actions = {
        IconButton(onClick = vm::undo, enabled = s.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.layout_undo)) }
        IconButton(onClick = vm::redo, enabled = s.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.layout_redo)) }
        TextButton(onClick = { vm.save(onBack) }, enabled = !s.loading) {
            Icon(Icons.Filled.Check, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_save))
        }
    }) { padding ->
        if (s.loading) { LoadingBox(Modifier.padding(padding)); return@AppScaffold }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).then(Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 560.dp))) {
            Text(stringResource(R.string.layout_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.layout_columns), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    (3..5).forEachIndexed { i, n ->
                        SegmentedButton(s.columns == n, { vm.setColumns(n) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text("$n") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ profilesOpen = true }, { Text(stringResource(R.string.profiles_title)) },
                    leadingIcon = { Icon(Icons.Filled.Bookmarks, null, Modifier.size(18.dp)) })
                AssistChip({ vm.tidy() }, { Text(stringResource(R.string.layout_tidy)) }, leadingIcon = { Icon(Icons.Filled.VerticalAlignTop, null, Modifier.size(18.dp)) })
                AssistChip({ vm.autoLayout() }, { Text(stringResource(R.string.layout_auto)) }, leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(18.dp)) })
                AssistChip({ vm.clear() }, { Text(stringResource(R.string.layout_clear)) }, leadingIcon = { Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp)) })
                AssistChip({ vm.useStandard(onBack) }, { Text(stringResource(R.string.layout_standard)) }, leadingIcon = { Icon(Icons.Filled.Restore, null, Modifier.size(18.dp)) })
            }
            Spacer(Modifier.height(16.dp))
            EditorGrid(
                style = s.style ?: com.universalir.core.ui.LocalAppSettings.current.remoteStyle,
                s.buttons, s.columns,
                onEmpty = { r, c ->
                    val first = s.commands.firstOrNull { cmd -> s.buttons.none { it.commandName == cmd.name } } ?: s.commands.firstOrNull()
                    if (first != null) editing = KeyEdit(CustomButton("new", first.label, suggestIcon(first.name), first.name, r, c), isNew = true)
                },
                onKey = { editing = KeyEdit(it, isNew = false) },
                onMove = vm::moveTo,
                onDeleteRow = vm::deleteRow,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
    if (profilesOpen) ProfilesSheet(s.profiles, onSave = vm::saveProfile, onLoad = { vm.loadProfile(it); profilesOpen = false },
        onDelete = vm::deleteProfile, onDismiss = { profilesOpen = false })
    editing?.let { e ->
        KeyEditorSheet(e.button, e.isNew, s.commands, s.columns,
            onTest = vm::test,
            onDuplicate = { vm.duplicate(e.button); editing = null },
            onInsertRow = { vm.insertRow(e.button.row); editing = null },
            onDeleteRow = { vm.deleteRow(e.button.row); editing = null },
            onSave = { b -> if (e.isNew) vm.addAt(b.row, b.col, b) else vm.update(b); editing = null },
            onDelete = { vm.remove(e.button.id); editing = null },
            onDismiss = { editing = null })
    }
}

/** Grid canvas: dashed "+" cells where nothing is placed, keys that can be tapped or dragged. */
@Composable
private fun EditorGrid(
    style: com.universalir.domain.model.RemoteStyle,
    buttons: List<CustomButton>, columns: Int,
    onEmpty: (Int, Int) -> Unit, onKey: (CustomButton) -> Unit, onMove: (String, Int, Int) -> Unit,
    onDeleteRow: ((Int) -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 12.dp
        val cell = ((maxWidth - gap * (columns - 1)) / columns).coerceAtMost(88.dp)
        val gridWidth = cell * columns + gap * (columns - 1)
        val startPad = (maxWidth - gridWidth) / 2
        val rows = maxOf(rowsOf(buttons) + 2, 6)
        val step = with(density) { (cell + gap).toPx() }
        val taken = buttons.flatMap { b -> (b.row until b.row + b.h).flatMap { r -> (b.col until b.col + b.w).map { r to it } } }.toSet()
        // Where the key being dragged would land: (row, col, w, h).
        var target by remember { mutableStateOf<List<Int>?>(null) }
        Box(Modifier.fillMaxWidth().height(cell * rows + gap * (rows - 1))) {
            target?.let { (r, c, w, h) ->
                Box(Modifier.offset(x = startPad + (cell + gap) * c, y = (cell + gap) * r).size(cell * w + gap * (w - 1), cell * h + gap * (h - 1))
                    .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)))
            }
            // Same backgrounds as the remote: rocker pills and the round D-pad behind their keys.
            val volLabel = stringResource(R.string.btn_volume)
            val chLabel = stringResource(R.string.btn_channel)
            val rockers = remember(buttons, volLabel, chLabel) { findRockers(buttons, volLabel, chLabel) }
            val pad = remember(buttons) { dpadCentre(buttons) }
            val onPad = remember(buttons, pad, rockers) {
                val cross = pad?.let { (r, c) -> setOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1) }.orEmpty()
                buttons.filter { (it.row to it.col) in cross && it.commandName in setOf("up", "down", "left", "right") }.map { it.id }.toSet() +
                    rockers.flatMap { it.keys }
            }
            rockers.forEach { rk ->
                val h = cell * (rk.bottom - rk.top + 1) + gap * (rk.bottom - rk.top)
                Box(Modifier.offset(x = startPad + (cell + gap) * rk.col, y = (cell + gap) * rk.top).size(cell, h), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().padding(horizontal = cell * 0.04f).clip(RoundedCornerShape(50)).background(padBrush(style))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)))
                    rk.label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            pad?.let { (r, c) ->
                val size = cell * 3 + gap * 2
                Box(Modifier.offset(x = startPad + (cell + gap) * (c - 1), y = (cell + gap) * (r - 1)).size(size).clip(CircleShape)
                    .background(padBrush(style)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
            }
            // Free cells: a light "+" so the layout reads like the real remote.
            val rocks = rockers.flatMap { rk -> (rk.top..rk.bottom).map { it to rk.col } }.toSet()
            val padCells = pad?.let { (pr, pc) -> (pr - 1..pr + 1).flatMap { rr -> (pc - 1..pc + 1).map { rr to it } } }.orEmpty().toSet()
            for (r in 0 until rows) for (c in 0 until columns) if ((r to c) !in taken && (r to c) !in rocks && (r to c) !in padCells) {
                Box(
                    Modifier.offset(x = startPad + (cell + gap) * c, y = (cell + gap) * r).size(cell)
                        .padding(cell * 0.12f)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), CircleShape)
                        .clickable { onEmpty(r, c) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, stringResource(R.string.layout_add), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)) }
            }
            // Empty cells inside the D-pad / rocker area stay tappable but invisible.
            for (cellPos in (rocks + padCells)) if (cellPos !in taken && cellPos.first in 0 until rows && cellPos.second in 0 until columns) {
                val (r, c) = cellPos
                Box(Modifier.offset(x = startPad + (cell + gap) * c, y = (cell + gap) * r).size(cell).clip(CircleShape).clickable { onEmpty(r, c) })
            }
            // An empty row with keys below it gets a remove badge, so gaps can be closed in one tap.
            val used = rowsOf(buttons)
            if (onDeleteRow != null) for (r in 0 until used) if ((0 until columns).none { (r to it) in taken }) {
                val label = stringResource(R.string.layout_delete_row)
                Box(
                    Modifier.offset(x = startPad + gridWidth - 32.dp, y = (cell + gap) * r + 4.dp).size(28.dp).zIndex(2f)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer)
                        .clickable(onClickLabel = label) { onDeleteRow(r) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, label, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer) }
            }
            buttons.forEach { b ->
                var drag by remember(b.id, b.row, b.col) { mutableStateOf(Offset.Zero) }
                var dragging by remember(b.id) { mutableStateOf(false) }
                Box(
                    Modifier.offset(x = startPad + (cell + gap) * b.col, y = (cell + gap) * b.row)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationX = drag.x; translationY = drag.y
                            if (dragging) { scaleX = 1.08f; scaleY = 1.08f; alpha = 0.92f }
                        }
                        .pointerInput(b.id, b.row, b.col, columns) {
                            detectDragGestures(
                                onDragStart = { dragging = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onDrag = { change, amount ->
                                    change.consume(); drag += amount
                                    val col = (b.col + (drag.x / step).roundToInt()).coerceIn(0, columns - b.w)
                                    val row = (b.row + (drag.y / step).roundToInt()).coerceAtLeast(0)
                                    val next = listOf(row, col, b.w, b.h)
                                    if (next != target) { target = next; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                },
                                onDragEnd = {
                                    val col = (b.col + (drag.x / step).roundToInt()).coerceIn(0, columns - b.w)
                                    val row = (b.row + (drag.y / step).roundToInt()).coerceAtLeast(0)
                                    dragging = false; drag = Offset.Zero; target = null
                                    onMove(b.id, row, col)
                                },
                                onDragCancel = { dragging = false; drag = Offset.Zero; target = null },
                            )
                        },
                ) {
                    GridKey(b, cell * b.w + gap * (b.w - 1), cell * b.h + gap * (b.h - 1), onClick = { onKey(b) }, style = style, onPad = b.id in onPad)
                }
            }
        }
    }
}

/** Add/edit one key: action, label, icon, colour, shape, size, label visibility, with a live preview. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KeyEditorSheet(
    initial: CustomButton, isNew: Boolean, commands: List<LayoutCommand>, columns: Int,
    onTest: (String) -> Unit, onDuplicate: () -> Unit, onInsertRow: () -> Unit, onDeleteRow: () -> Unit, onSave: (CustomButton) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    var b by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (picking) {
            CommandPicker(commands, b.commandName) { cmd ->
                // A new action brings its own label and icon unless the user already customised them.
                val keepLabel = b.label != (commands.firstOrNull { it.name == b.commandName }?.label ?: b.commandName)
                b = b.copy(commandName = cmd.name, label = if (keepLabel) b.label else cmd.label,
                    icon = if (b.icon == suggestIcon(b.commandName)) suggestIcon(cmd.name) else b.icon)
                picking = false
            }
            return@ModalBottomSheet
        }
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (isNew) R.string.layout_add else R.string.layout_edit), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                GridKey(b.copy(w = 1, h = 1), 64.dp, 64.dp, onClick = { onTest(b.commandName) })
            }
            Spacer(Modifier.height(14.dp))
            // Action
            Text(stringResource(R.string.layout_command), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Surface(onClick = { picking = true }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(commands.firstOrNull { it.name == b.commandName }?.label ?: b.commandName, style = MaterialTheme.typography.bodyLarge)
                        LtrText(b.commandName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.KeyboardArrowDown, null)
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(b.label, { b = b.copy(label = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.layout_label)) })
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.layout_show_label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(b.showLabel, { b = b.copy(showLabel = it) })
            }
            Spacer(Modifier.height(12.dp))
            // Icon
            Text(stringResource(R.string.device_icon), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconChoice(selected = b.icon.isEmpty(), onClick = { b = b.copy(icon = "") }) {
                    Text("Aa", style = MaterialTheme.typography.titleSmall)
                }
                buttonIcons.forEach { (key, vector) ->
                    IconChoice(selected = b.icon == key, onClick = { b = b.copy(icon = key) }) { Icon(vector, key, Modifier.size(22.dp)) }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Colour
            Text(stringResource(R.string.device_color), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(MaterialTheme.colorScheme.surfaceContainerHigh, b.color == null) { b = b.copy(color = null) }
                keyColors.forEachIndexed { i, c -> Swatch(c, b.color == i) { b = b.copy(color = i) } }
            }
            Spacer(Modifier.height(16.dp))
            // Shape
            Text(stringResource(R.string.layout_shape), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val shapes = listOf(KeyShape.ROUND to R.string.shape_round, KeyShape.SQUARE to R.string.shape_square, KeyShape.PILL to R.string.shape_pill)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                shapes.forEachIndexed { i, (v, res) ->
                    SegmentedButton(b.shape == v, { b = b.copy(shape = v) }, SegmentedButtonDefaults.itemShape(i, shapes.size)) { Text(stringResource(res)) }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Size
            Text(stringResource(R.string.layout_size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Stepper(stringResource(R.string.layout_width), b.w, 1..minOf(3, columns)) { b = b.copy(w = it) }
            Stepper(stringResource(R.string.layout_height), b.h, 1..3) { b = b.copy(h = it) }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onTest(b.commandName) }, Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.trial_test))
                }
                Button(onClick = { onSave(b) }, Modifier.weight(1f).height(52.dp), enabled = b.label.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            }
            if (!isNew) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDuplicate, Modifier.weight(1f)) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_duplicate), maxLines = 1)
                }
                OutlinedButton(onClick = onInsertRow, Modifier.weight(1f)) {
                    Icon(Icons.Filled.VerticalAlignBottom, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.layout_insert_row), maxLines = 1)
                }
            }
            if (!isNew) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDeleteRow, Modifier.weight(1f)) {
                    Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.layout_delete_row), color = MaterialTheme.colorScheme.error, maxLines = 1)
                }
                TextButton(onClick = onDelete, Modifier.weight(1f)) {
                    Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun IconChoice(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(46.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Filled.Check, null, tint = if (color.red + color.green + color.blue > 2.2f) Color.Black else Color.White) }
}

/** Searchable list of every command the device's code set has. */
@Composable
private fun CommandPicker(commands: List<LayoutCommand>, current: String, onPick: (LayoutCommand) -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = TextNormalizer.normalize(query)
    val shown = if (q.isEmpty()) commands else commands.filter { TextNormalizer.normalize(it.label + it.name).contains(q) }
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(stringResource(R.string.layout_search)) })
        LazyColumn(Modifier.heightIn(max = 480.dp).padding(top = 8.dp)) {
            items(shown, key = { it.name }) { c ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onPick(c) }
                    .background(if (c.name == current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val icon = buttonIcons[suggestIcon(c.name)]
                    Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        if (icon != null) Icon(icon, null, Modifier.size(20.dp)) else Text(shortLabel(c.name, c.label).take(2), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LtrText(c.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** "Label   −  2  +" row used for key width and height. */
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > range.first, shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = { onChange(value + 1) }, enabled = value < range.last, shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("+") }
    }
}

/** Saved layouts: save the current one under a name, load or delete others. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilesSheet(
    profiles: List<com.universalir.domain.model.LayoutProfile>, onSave: (String) -> Unit,
    onLoad: (com.universalir.domain.model.LayoutProfile) -> Unit, onDelete: (com.universalir.domain.model.LayoutProfile) -> Unit, onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.profiles_name)) })
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(name); name = "" }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            }
            Spacer(Modifier.height(12.dp))
            if (profiles.isEmpty()) Text(stringResource(R.string.profiles_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            profiles.sortedByDescending { it.savedAt }.forEach { p ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.profiles_info, p.buttons.size, p.columns), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onLoad(p) }) { Text(stringResource(R.string.profiles_load)) }
                        IconButton(onClick = { onDelete(p) }) { Icon(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
