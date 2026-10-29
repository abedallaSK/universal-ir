package com.universalir.feature.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Palette
import com.universalir.domain.model.RemoteStyle
import com.universalir.domain.model.CustomButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.theme.LocalRemoteMetrics
import com.universalir.core.ui.AppIcons
import com.universalir.core.ui.DeviceAvatar
import com.universalir.core.ui.look
import com.universalir.core.ui.categoryName
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.LoadingBox
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.RemoteButton
import com.universalir.core.ui.SectionHeader
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.describeOutcome
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.LoadResult
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.domain.model.categoryOf
import com.universalir.domain.usecase.RemoteController
import com.universalir.domain.usecase.SendOutcome
import com.universalir.ir.format.CommandNames as N
import com.universalir.ir.protocol.DeviceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ------------------------------------------------------------------ Router

@HiltViewModel
class RemoteRouterViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val devices: DeviceRepository,
    private val codes: CodeRepository,
) : ViewModel() {
    val deviceId: Long = handle["deviceId"] ?: -1
    val target = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val d = devices.device(deviceId) ?: return@launch target.update { "missing" }
            target.value = when (val r = codes.load(d.codeSetId)) {
                is LoadResult.Ok -> if (r.set is LoadedCodeSet.CommandSet) "cmd" else "ac"
                is LoadResult.Error -> "cmd" // the command screen shows the load error with a next action
            }
        }
    }
}

@Composable
fun RemoteRouterScreen(onAc: (Long) -> Unit, onCommands: (Long) -> Unit, onBack: () -> Unit, vm: RemoteRouterViewModel = hiltViewModel()) {
    val target by vm.target.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        when (target) {
            "ac" -> onAc(vm.deviceId)
            "cmd" -> onCommands(vm.deviceId)
        }
    }
    if (target == "missing") {
        AppScaffold("", onBack = onBack) { p ->
            StatusPanel(Icons.Filled.ErrorOutline, stringResource(R.string.state_corrupt_title), stringResource(R.string.state_device_missing),
                Modifier.padding(p), actionLabel = stringResource(R.string.action_back), onAction = onBack)
        }
    } else LoadingBox()
}

// ------------------------------------------------------------------ Command remote (pages 10–13)

data class CommandUiState(
    val loading: Boolean = true,
    val device: SavedDeviceEntity? = null,
    val set: LoadedCodeSet.CommandSet? = null,
    val error: String? = null,
    val corrupt: Boolean = false,
    val settings: DeviceSettings = DeviceSettings(),
    val outcome: SendOutcome? = null,
)

@HiltViewModel
class CommandRemoteViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val devices: DeviceRepository,
    private val codes: CodeRepository,
    private val remote: RemoteController,
    private val appSettings: com.universalir.data.repository.SettingsRepository,
) : ViewModel() {
    private val deviceId: Long = handle["deviceId"] ?: -1

    fun setTouchpad(on: Boolean) = viewModelScope.launch { devices.updateSettings(deviceId) { it.copy(touchpad = on) } }

    /** forAll: becomes the app default and this remote follows it again. */
    fun setStyle(style: RemoteStyle, forAll: Boolean) = viewModelScope.launch {
        if (forAll) { appSettings.setRemoteStyle(style); devices.updateSettings(deviceId) { it.copy(remoteStyle = null) } }
        else devices.updateSettings(deviceId) { it.copy(remoteStyle = style) }
    }
    private val _ui = MutableStateFlow(CommandUiState())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch { load() }
        // Pick up layout/appearance edits made on other screens (layout editor, long-press sheet).
        viewModelScope.launch {
            devices.observeDevice(deviceId).collect { d ->
                val s = d?.let { DeviceSettings.decode(it.customSettings) } ?: return@collect
                if (!_ui.value.loading) _ui.update { it.copy(settings = s, device = d) }
            }
        }
    }

    private suspend fun load() {
        val d = devices.device(deviceId) ?: return _ui.update { it.copy(loading = false, error = "missing") }
        val ds = DeviceSettings.decode(d.customSettings) ?: return _ui.update { it.copy(loading = false, corrupt = true, device = d) }
        when (val r = codes.load(d.codeSetId)) {
            is LoadResult.Ok -> {
                val set = r.set as? LoadedCodeSet.CommandSet
                _ui.value = CommandUiState(loading = false, device = d, set = set, settings = ds, error = if (set == null) "not_commands" else null)
                devices.markUsed(d.id)
            }
            is LoadResult.Error -> _ui.value = CommandUiState(loading = false, device = d, error = r.reason)
        }
    }

    fun send(command: String) = viewModelScope.launch {
        val set = _ui.value.set ?: return@launch
        val outcome = remote.sendCommand(set, command)
        _ui.update { it.copy(outcome = outcome) }
    }

    fun toggleHidden(command: String) = saveSettings { s ->
        s.copy(hiddenButtons = if (command in s.hiddenButtons) s.hiddenButtons - command else s.hiddenButtons + command)
    }

    fun move(order: List<String>, from: Int, to: Int) = saveSettings { s ->
        if (to !in order.indices) return@saveSettings s
        val list = order.toMutableList(); val item = list.removeAt(from); list.add(to, item)
        s.copy(buttonOrder = list)
    }

    fun favorite(command: String, label: String) = viewModelScope.launch {
        devices.addFavoriteCommand(deviceId, label, command, null)
    }

    fun resetCorrupt() = viewModelScope.launch {
        devices.saveSettings(deviceId, DeviceSettings())
        _ui.value = CommandUiState(); load()
    }

    private fun saveSettings(change: (DeviceSettings) -> DeviceSettings) = viewModelScope.launch {
        val next = change(_ui.value.settings)
        devices.saveSettings(deviceId, next)
        _ui.update { it.copy(settings = next) }
    }
}

private val layoutCommands = setOf(
    N.POWER, N.POWER_ON, N.POWER_OFF, N.INPUT, N.MUTE, N.UP, N.DOWN, N.LEFT, N.RIGHT, N.OK, N.BACK, N.HOME, N.MENU,
    N.VOL_UP, N.VOL_DOWN, N.CH_UP, N.CH_DOWN, N.PLAY, N.PAUSE, N.STOP, N.NEXT, N.PREV, N.FAST_FORWARD, N.REWIND,
    N.OSCILLATE, N.TIMER, N.MODE, N.LIGHT, N.SPEED, N.KEYSTONE_UP, N.KEYSTONE_DOWN, N.FOCUS_IN, N.FOCUS_OUT,
) + (0..9).map { "digit_$it" } + extraKeys.map { it.first } + colorKeys.map { it.first } + lightCommands

/** Secondary TV/box keys found in many code sets (generic Android boxes in particular). */
private val extraKeys get() = listOf(
    "info" to R.string.btn_info, "guide" to R.string.btn_guide, "settings" to R.string.btn_settings, "exit" to R.string.btn_exit,
    "subtitle" to R.string.btn_subtitle, "audio" to R.string.btn_audio_track, "apps" to R.string.btn_apps, "mouse" to R.string.btn_mouse,
    "list" to R.string.btn_list, "last" to R.string.btn_last, "record" to R.string.btn_record, "sleep" to R.string.btn_sleep,
    "aspect" to R.string.btn_aspect, "page_up" to R.string.btn_page_up, "page_down" to R.string.btn_page_down,
    "youtube" to R.string.btn_youtube, "netflix" to R.string.btn_netflix,
)
private val colorKeys get() = listOf(
    "red" to Color(0xFFE53935), "green" to Color(0xFF43A047), "yellow" to Color(0xFFFDD835), "blue" to Color(0xFF1E88E5),
)
private val lightCommands get() = listOf("power_on", "power_off", "brightness_up", "brightness_down", "speed_up", "speed_down")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandRemoteScreen(onBack: () -> Unit, onDesigner: (Long) -> Unit, onDiagnostics: () -> Unit, onEditLayout: (Long) -> Unit, vm: CommandRemoteViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val favAdded = stringResource(R.string.fav_added)
    var longPressed by remember { mutableStateOf<String?>(null) }
    var stylePicker by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val style = ui.settings.remoteStyle ?: com.universalir.core.ui.LocalAppSettings.current.remoteStyle
    LaunchedEffect(ui.outcome) {
        val o = ui.outcome
        if (o != null && o !is SendOutcome.Success) snackbar.showSnackbar(describeOutcome(context, o))
    }

    AppScaffold(
        ui.device?.displayName ?: "", onBack = onBack, snackbar = snackbar,
        actions = {
            if (ui.set != null) {
                IconButton(onClick = { ui.device?.let { onEditLayout(it.id) } }) { Icon(Icons.Filled.Edit, stringResource(R.string.remote_edit_layout)) }
                if ((ui.set?.commandNames?.let { names -> listOf("up", "down", "left", "right").count { it in names } } ?: 0) >= 3) {
                    IconButton(onClick = { vm.setTouchpad(!ui.settings.touchpad) }) {
                        Icon(if (ui.settings.touchpad) AppIcons.Arrange else AppIcons.Mouse, stringResource(R.string.touchpad_toggle))
                    }
                }
                IconButton(onClick = { stylePicker = true }) { Icon(Icons.Filled.Palette, stringResource(R.string.style_title)) }
            }
        },
    ) { padding ->
        val set = ui.set
        when {
            ui.loading -> LoadingBox(Modifier.padding(padding))
            ui.corrupt -> StatusPanel(Icons.Filled.ErrorOutline, stringResource(R.string.state_corrupt_title), stringResource(R.string.state_corrupt_body),
                Modifier.padding(padding), actionLabel = stringResource(R.string.state_corrupt_action), onAction = vm::resetCorrupt)
            set == null -> StatusPanel(Icons.Filled.ErrorOutline, stringResource(R.string.state_unsupported_title),
                stringResource(R.string.state_unsupported_body, ui.error ?: ""), Modifier.padding(padding), actionLabel = stringResource(R.string.action_back), onAction = onBack)
            else -> {
                val available = set.commandNames - ui.settings.hiddenButtons
                val labelOf: (String) -> String = { name -> set.codes.commands.firstOrNull { it.name == name }?.label ?: name }
                val onLong: (String) -> Unit = { c -> longPressed = c }
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ui.device?.let { RemoteHeader(it, onClick = { info = true }) }
                    Spacer(Modifier.height(20.dp))
                    val custom = ui.settings.customButtons.filter { it.commandName in set.commandNames && it.commandName !in ui.settings.hiddenButtons }
                    val category = categoryOf(ui.device?.category)
                    val names = set.codes.commands.map { it.name }.filter { it in available }
                    val cols = if (custom.isEmpty()) standardColumns(names, category) else ui.settings.layoutColumns.coerceIn(3, 5)
                    // The standard remote is the same smart grid the layout editor starts from.
                    val layout = remember(custom, names, cols, category) { custom.ifEmpty { defaultLayout(names, labelOf, cols, category) } }
                    RemoteGrid(layout, cols, onSend = vm::send, onLong = onLong, style = style, touchpad = ui.settings.touchpad)
                    // A custom layout may leave commands out; they stay one tap away.
                    val extra = if (custom.isEmpty()) emptyList() else names.filter { n -> custom.none { it.commandName == n } }
                    if (extra.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader(stringResource(R.string.remote_more))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            extra.forEach { c -> TextCommand(labelOf(c), { vm.send(c) }) }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
    if (stylePicker) RemoteStyleSheet(style, onPick = { st, all -> vm.setStyle(st, all); stylePicker = false }, onDismiss = { stylePicker = false })
    if (info) ui.device?.let { d -> DeviceInfoSheet(d, ui.set?.commandNames?.size ?: 0, style) { info = false } }
    longPressed?.let { cmd ->
        val label = ui.set?.codes?.commands?.firstOrNull { it.name == cmd }?.label ?: cmd
        KeyMenuSheet(label,
            onFavorite = { vm.favorite(cmd, label); longPressed = null; scope.launch { snackbar.showSnackbar(favAdded) } },
            onEditLayout = { longPressed = null; ui.device?.let { onEditLayout(it.id) } },
            onDismiss = { longPressed = null })
    }
}

/** Long-press menu for a remote key. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun KeyMenuSheet(label: String, onFavorite: () -> Unit, onEditLayout: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            LtrText(label, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            KeyMenuRow(AppIcons.Star, stringResource(R.string.action_favorite), onFavorite)
            KeyMenuRow(Icons.Filled.Edit, stringResource(R.string.remote_edit_layout), onEditLayout)
        }
    }
}

@Composable
private fun KeyMenuRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun orderedExtras(set: LoadedCodeSet.CommandSet, s: DeviceSettings): List<String> {
    val names = set.codes.commands.map { it.name }
    return s.buttonOrder.filter { it in names } + names.filter { it !in s.buttonOrder }
}

@Composable
private fun TextCommand(label: String, onClick: () -> Unit) {
    val feedback = com.universalir.core.ui.rememberFeedback()
    Surface(onClick = { feedback.click(); onClick() }, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        LtrText(label, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun Btn(cmd: String, icon: ImageVector?, label: String, available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit, emphasized: Boolean = false) {
    if (cmd in available) RemoteButton(icon, label, { send(cmd) }, emphasized = emphasized, onLongClick = { onLong(cmd) })
}

@Composable
private fun PowerRow(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Btn(N.POWER, AppIcons.Power, stringResource(R.string.btn_power), available, send, onLong, emphasized = true)
        Btn(N.POWER_ON, AppIcons.Power, stringResource(R.string.btn_power_on), available, send, onLong, emphasized = true)
        Btn(N.POWER_OFF, AppIcons.Power, stringResource(R.string.btn_power_off), available, send, onLong)
        Btn(N.INPUT, AppIcons.Input, stringResource(R.string.btn_input), available, send, onLong)
        Btn(N.MUTE, AppIcons.Mute, stringResource(R.string.btn_mute), available, send, onLong)
        trailing()
    }
}

@Composable
private fun DPad(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit) {
    if (listOf(N.UP, N.DOWN, N.LEFT, N.RIGHT, N.OK).none { it in available }) return
    val size = LocalRemoteMetrics.current.padSize
    Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
        Box(Modifier.align(Alignment.TopCenter).padding(6.dp)) { Btn(N.UP, AppIcons.Up, stringResource(R.string.btn_up), available, send, onLong) }
        Box(Modifier.align(Alignment.BottomCenter).padding(6.dp)) { Btn(N.DOWN, AppIcons.Down, stringResource(R.string.btn_down), available, send, onLong) }
        // Left/right are physical directions on the remote, so they are not mirrored in RTL.
        Box(Modifier.align(Alignment.CenterStart).padding(6.dp)) { Btn(N.LEFT, AppIcons.Left, stringResource(R.string.btn_left), available, send, onLong) }
        Box(Modifier.align(Alignment.CenterEnd).padding(6.dp)) { Btn(N.RIGHT, AppIcons.Right, stringResource(R.string.btn_right), available, send, onLong) }
        Btn(N.OK, null, stringResource(R.string.btn_ok), available, send, onLong, emphasized = true)
    }
}

@Composable
private fun Rockers(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit, channels: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (N.VOL_UP in available || N.VOL_DOWN in available) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Btn(N.VOL_UP, AppIcons.VolumeUp, stringResource(R.string.btn_vol_up), available, send, onLong)
            Text(stringResource(R.string.btn_volume), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(6.dp))
            Btn(N.VOL_DOWN, AppIcons.VolumeDown, stringResource(R.string.btn_vol_down), available, send, onLong)
        }
        if (channels && (N.CH_UP in available || N.CH_DOWN in available)) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Btn(N.CH_UP, AppIcons.Up, stringResource(R.string.btn_ch_up), available, send, onLong)
            Text(stringResource(R.string.btn_channel), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(6.dp))
            Btn(N.CH_DOWN, AppIcons.Down, stringResource(R.string.btn_ch_down), available, send, onLong)
        }
    }
}

@Composable
private fun NavRow(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Btn(N.BACK, AppIcons.Back, stringResource(R.string.btn_back), available, send, onLong)
        Btn(N.HOME, AppIcons.Home, stringResource(R.string.btn_home), available, send, onLong)
        Btn(N.MENU, AppIcons.Menu, stringResource(R.string.btn_menu), available, send, onLong)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Playback(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit) {
    val cmds = listOf(N.PREV, N.REWIND, N.PLAY, N.PAUSE, N.STOP, N.FAST_FORWARD, N.NEXT)
    if (cmds.none { it in available }) return
    // Media transport keeps its universal left-to-right order in every locale.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Btn(N.PREV, AppIcons.Previous, stringResource(R.string.btn_prev), available, send, onLong)
        Btn(N.REWIND, AppIcons.Rewind, stringResource(R.string.btn_rew), available, send, onLong)
        Btn(N.PLAY, AppIcons.Play, stringResource(R.string.btn_play), available, send, onLong)
        Btn(N.PAUSE, AppIcons.Pause, stringResource(R.string.btn_pause), available, send, onLong)
        Btn(N.STOP, AppIcons.Stop, stringResource(R.string.btn_stop), available, send, onLong)
        Btn(N.FAST_FORWARD, AppIcons.FastForward, stringResource(R.string.btn_ff), available, send, onLong)
        Btn(N.NEXT, AppIcons.Next, stringResource(R.string.btn_next), available, send, onLong)
    }
}

@Composable
private fun Keypad(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit) {
    if ((0..9).none { "digit_$it" in available }) return
    SectionHeader(stringResource(R.string.remote_numbers))
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(null, 0, null))
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            row.forEach { d ->
                if (d == null || "digit_$d" !in available) Spacer(Modifier.size(LocalRemoteMetrics.current.button))
                else RemoteButton(null, d.toString(), { send("digit_$d") }, onLongClick = { onLong("digit_$d") })
            }
        }
    }
}

@Composable
private fun TvLayout(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit) {
    PowerRow(available, send, onLong)
    Spacer(Modifier.height(24.dp))
    DPad(available, send, onLong)
    Spacer(Modifier.height(16.dp))
    NavRow(available, send, onLong)
    Spacer(Modifier.height(20.dp))
    Rockers(available, send, onLong, channels = true)
    ColorKeys(available, send, onLong)
    Spacer(Modifier.height(16.dp))
    Playback(available, send, onLong)
    ExtraKeys(available, send)
    Keypad(available, send, onLong)
}

@Composable
fun RemoteHeader(d: SavedDeviceEntity, onClick: (() -> Unit)? = null) {
    val cat = categoryOf(d.category)
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(d.look(), size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(categoryName(cat)), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.brandName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.remote_ir_ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onClick != null) Icon(AppIcons.Info, stringResource(R.string.info_title), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val m = Modifier.fillMaxWidth().padding(top = 4.dp)
    if (onClick != null) Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = m) { content() }
    else Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = m) { content() }
}

private val styleNames = mapOf(
    RemoteStyle.CLASSIC to R.string.style_classic, RemoteStyle.MODERN to R.string.style_modern, RemoteStyle.MINIMAL to R.string.style_minimal,
    RemoteStyle.COMPACT to R.string.style_compact, RemoteStyle.VIVID to R.string.style_vivid,
)
private val styleDescriptions = mapOf(
    RemoteStyle.CLASSIC to R.string.style_classic_desc, RemoteStyle.MODERN to R.string.style_modern_desc, RemoteStyle.MINIMAL to R.string.style_minimal_desc,
    RemoteStyle.COMPACT to R.string.style_compact_desc, RemoteStyle.VIVID to R.string.style_vivid_desc,
)

@androidx.annotation.StringRes
fun remoteStyleName(s: RemoteStyle): Int = styleNames.getValue(s)

/** Picker for the button style, each option with a live sample of keys drawn in that style. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RemoteStyleSheet(current: RemoteStyle, onPick: (RemoteStyle, forAll: Boolean) -> Unit, onDismiss: () -> Unit) {
    var forAll by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.style_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
            RemoteStyle.entries.forEach { st ->
                val selected = st == current
                Surface(
                    onClick = { onPick(st, forAll) }, shape = MaterialTheme.shapes.large,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Live sample: power, volume and play in this style.
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                CustomButton("p", "Power", "power", N.POWER, color = 1), CustomButton("v", "Vol", "vol_up", N.VOL_UP),
                                CustomButton("o", "OK", "ok", N.OK, color = 0),
                            ).forEach { k -> GridKey(k, 44.dp, 44.dp, onClick = { onPick(st, forAll) }, style = st) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(remoteStyleName(st)), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(styleDescriptions.getValue(st)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { forAll = !forAll }.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.style_all), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Switch(forAll, { forAll = it })
            }
        }
    }
}

/** "More info" about a device: what it is, how many buttons, when it was added. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DeviceInfoSheet(d: SavedDeviceEntity, buttons: Int, style: RemoteStyle, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(d.look(), size = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(d.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.info_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(d.createdAt))
            listOf(
                stringResource(R.string.detail_category) to stringResource(categoryName(categoryOf(d.category))),
                stringResource(R.string.field_brand) to d.brandName,
                stringResource(R.string.info_buttons) to buttons.toString(),
                stringResource(R.string.style_title) to stringResource(remoteStyleName(style)),
                stringResource(R.string.info_added) to date,
            ).forEach { (k, v) -> com.universalir.core.ui.KeyValueRow(k, v) }
        }
    }
}

@Composable
private fun ColorKeys(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit) {
    val keys = colorKeys.filter { it.first in available }
    if (keys.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        keys.forEach { (cmd, color) -> ColorDot(color, cmd, Modifier.size(width = 56.dp, height = 28.dp), RoundedCornerShape(14.dp), { send(cmd) }, { onLong(cmd) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraKeys(available: Set<String>, send: (String) -> Unit) {
    val keys = extraKeys.filter { it.first in available }
    if (keys.isEmpty()) return
    SectionHeader(stringResource(R.string.remote_shortcuts))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        keys.forEach { (cmd, label) -> TextCommand(stringResource(label)) { send(cmd) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorDot(color: Color, label: String, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit, onLongClick: () -> Unit) {
    val feedback = com.universalir.core.ui.rememberFeedback()
    Box(
        modifier.clip(shape).background(color).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = { feedback.click(); onClick() }, onLongClick = onLongClick)
            .semantics { contentDescription = label },
    )
}

/** Swatch colours for LED strip/bulb commands named "color:<name>". */
private val ledColors = linkedMapOf(
    "red" to Color(0xFFFF1744), "light_red" to Color(0xFFFF5252), "red_1" to Color(0xFFFF1744), "red_2" to Color(0xFFFF3D00), "red_3" to Color(0xFFFF6E40),
    "red_4" to Color(0xFFFF9E80), "dark_orange" to Color(0xFFFF6D00), "orange" to Color(0xFFFF9100), "light_orange" to Color(0xFFFFAB40),
    "gold" to Color(0xFFFFC400), "yellow" to Color(0xFFFFEA00), "green" to Color(0xFF00E676), "green_1" to Color(0xFF00E676),
    "green_2" to Color(0xFF00C853), "green_3" to Color(0xFF1DE9B6), "green_4" to Color(0xFF64FFDA), "light_green" to Color(0xFF76FF03), "teal" to Color(0xFF1DE9B6),
    "aqua" to Color(0xFF18FFFF), "cyan" to Color(0xFF00E5FF), "light_cyan" to Color(0xFF84FFFF), "dark_cyan" to Color(0xFF00B8D4),
    "ice_blue" to Color(0xFFB3E5FC), "light_ice_blue" to Color(0xFFE1F5FE), "light_blue" to Color(0xFF40C4FF), "sky_blue" to Color(0xFF80D8FF), "blue" to Color(0xFF2979FF),
    "blue_1" to Color(0xFF2979FF), "blue_2" to Color(0xFF2962FF), "blue_3" to Color(0xFF448AFF), "blue_4" to Color(0xFF82B1FF),
    "dark_blue" to Color(0xFF304FFE), "indigo" to Color(0xFF3D5AFE), "violet" to Color(0xFF7C4DFF), "purple" to Color(0xFFD500F9),
    "dark_purple" to Color(0xFFAA00FF), "magenta" to Color(0xFFF50057), "pink" to Color(0xFFFF4081), "light_pink" to Color(0xFFFF80AB), "white" to Color(0xFFFFFFFF), "warm_white" to Color(0xFFFFE0B2), "cold_white" to Color(0xFFE3F2FD),
)

private fun ledColor(cmd: String): Color = ledColors[cmd.removePrefix("color:").lowercase()]
    // Unknown shade names still get a stable, distinct swatch.
    ?: Color.hsv(((cmd.hashCode() and 0x7fffffff) % 360).toFloat(), 0.8f, 1f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightLayout(available: Set<String>, labelOf: (String) -> String, send: (String) -> Unit, onLong: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Btn(N.POWER, AppIcons.Power, stringResource(R.string.btn_power), available, send, onLong, emphasized = true)
        Btn("power_on", AppIcons.Bulb, stringResource(R.string.btn_on), available, send, onLong, emphasized = true)
        Btn("power_off", AppIcons.Power, stringResource(R.string.btn_off), available, send, onLong)
        Btn(N.PLAY, AppIcons.Play, stringResource(R.string.btn_play), available, send, onLong)
        Btn(N.MODE, AppIcons.Tune, stringResource(R.string.btn_mode), available, send, onLong)
    }
    if ("brightness_up" in available || "brightness_down" in available) {
        SectionHeader(stringResource(R.string.light_brightness))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Btn("brightness_down", AppIcons.Minus, stringResource(R.string.btn_bright_down), available, send, onLong)
            Icon(AppIcons.Sun, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Btn("brightness_up", AppIcons.Plus, stringResource(R.string.btn_bright_up), available, send, onLong)
        }
    }
    val order = ledColors.keys.toList()
    val colors = available.filter { it.startsWith("color:") }.sortedBy { c -> order.indexOf(c.removePrefix("color:")).let { if (it < 0) 99 else it } }
    if (colors.isNotEmpty()) {
        SectionHeader(stringResource(R.string.light_colors))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            colors.forEach { c -> ColorDot(ledColor(c), labelOf(c), Modifier.size(52.dp), CircleShape, { send(c) }, { onLong(c) }) }
        }
    }
    val effects = available.filter { it.startsWith("effect:") }
    if (effects.isNotEmpty() || "speed_up" in available || "speed_down" in available) {
        SectionHeader(stringResource(R.string.light_effects))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            effects.forEach { c -> TextCommand(labelOf(c)) { send(c) } }
            if ("speed_down" in available) TextCommand(stringResource(R.string.btn_speed_down)) { send("speed_down") }
            if ("speed_up" in available) TextCommand(stringResource(R.string.btn_speed_up)) { send("speed_up") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioLayout(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit) {
    PowerRow(available, send, onLong) { Btn(N.MODE, AppIcons.Tune, stringResource(R.string.btn_mode), available, send, onLong) }
    Spacer(Modifier.height(20.dp))
    Rockers(available, send, onLong, channels = false)
    Spacer(Modifier.height(16.dp))
    Playback(available, send, onLong)
    DPad(available, send, onLong)
}

@Composable
private fun ProjectorLayout(available: Set<String>, send: (String) -> Unit, onLong: (String) -> Unit) {
    PowerRow(available, send, onLong)
    Spacer(Modifier.height(20.dp))
    DPad(available, send, onLong)
    Spacer(Modifier.height(12.dp))
    NavRow(available, send, onLong)
    // Keystone/focus are shown only when the code set really has them.
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Btn(N.KEYSTONE_UP, AppIcons.Keystone, stringResource(R.string.btn_keystone_up), available, send, onLong)
        Btn(N.KEYSTONE_DOWN, AppIcons.Keystone, stringResource(R.string.btn_keystone_down), available, send, onLong)
        Btn(N.FOCUS_IN, AppIcons.ZoomIn, stringResource(R.string.btn_focus_in), available, send, onLong)
        Btn(N.FOCUS_OUT, AppIcons.ZoomOut, stringResource(R.string.btn_focus_out), available, send, onLong)
    }
    Spacer(Modifier.height(16.dp))
    Rockers(available, send, onLong, channels = false)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FanLayout(available: Set<String>, labelOf: (String) -> String, send: (String) -> Unit, onLong: (String) -> Unit) {
    PowerRow(available, send, onLong)
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Btn(N.SPEED, AppIcons.Wind, stringResource(R.string.btn_speed), available, send, onLong)
        Btn(N.OSCILLATE, AppIcons.Swing, stringResource(R.string.btn_oscillate), available, send, onLong)
        Btn(N.TIMER, AppIcons.Timer, stringResource(R.string.btn_timer), available, send, onLong)
        Btn(N.MODE, AppIcons.Tune, stringResource(R.string.btn_mode), available, send, onLong)
        Btn(N.LIGHT, AppIcons.Bulb, stringResource(R.string.btn_light), available, send, onLong)
    }
    val speeds = available.filter { it.startsWith("speed:") || it.startsWith("reverse:") }.sorted()
    if (speeds.isNotEmpty()) {
        SectionHeader(stringResource(R.string.btn_speed))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            speeds.forEach { c -> TextCommand(labelOf(c)) { send(c) } }
        }
    }
}

/** Icon catalogue for the custom remote designer (same set as the layout editor). */
val designerIcons: Map<String, ImageVector> get() = buttonIcons

fun designerIcon(key: String): ImageVector? = buttonIcons[key]
