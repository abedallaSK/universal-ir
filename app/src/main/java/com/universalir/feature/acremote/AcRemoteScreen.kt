package com.universalir.feature.acremote
import kotlin.math.atan2
import com.universalir.core.ui.rememberFeedback
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.universalir.core.ui.AppIcons
import com.universalir.core.ui.readableWidth
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.AppCard
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.LoadingBox
import com.universalir.core.ui.LocalAppSettings
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.RemoteButton
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.VerificationBadge
import com.universalir.core.ui.describeOutcome
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.LoadResult
import com.universalir.data.repository.RemoteLayout
import com.universalir.data.repository.SettingsRepository
import com.universalir.data.repository.TemperatureUnit
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.domain.usecase.AcStateRules
import com.universalir.domain.usecase.RemoteController
import com.universalir.domain.usecase.SendOutcome
import com.universalir.ir.protocol.VerificationLevel
import com.universalir.ir.protocol.ac.AcCapabilities
import com.universalir.ir.protocol.ac.AcExtraCommand
import com.universalir.ir.protocol.ac.AcFeature
import com.universalir.ir.protocol.ac.AcMode
import com.universalir.ir.protocol.ac.AcState
import com.universalir.ir.protocol.ac.FanSpeed
import com.universalir.ir.protocol.ac.PowerControl
import com.universalir.ir.protocol.ac.SwingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AcUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val corrupt: Boolean = false,
    val device: SavedDeviceEntity? = null,
    val set: LoadedCodeSet? = null,
    val caps: AcCapabilities? = null,
    /** Per mode+fan temperature list for captured tables (null for protocol encoders). */
    val tableTemps: ((AcMode, FanSpeed) -> List<Int>)? = null,
    val state: AcState = AcState(),
    val lastSent: AcState? = null,
    /** When the off-timer fires (epoch ms), or null. */
    val offAt: Long? = null,
    val sending: Boolean = false,
    val outcome: SendOutcome? = null,
    val protocolId: String? = null,
    val protocolLevel: VerificationLevel? = null,
)

@HiltViewModel
class AcRemoteViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val devices: DeviceRepository,
    private val codes: CodeRepository,
    private val remote: RemoteController,
    private val settings: SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    private val deviceId: Long = handle["deviceId"] ?: -1

    /** Sets (minutes) or clears (null) the off-timer; it fires through WorkManager even if the app is closed. */
    fun setTimer(minutes: Int?) = viewModelScope.launch {
        val at = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        if (at == null) com.universalir.domain.usecase.AcTimer.cancel(appContext, deviceId)
        else com.universalir.domain.usecase.AcTimer.schedule(appContext, deviceId, at - System.currentTimeMillis())
        deviceSettings = deviceSettings.copy(acOffAt = at)
        devices.saveSettings(deviceId, deviceSettings)
        _ui.update { it.copy(offAt = at) }
    }
    private val _ui = MutableStateFlow(AcUiState())
    val ui = _ui.asStateFlow()
    private var sendJob: Job? = null
    private var deviceSettings = DeviceSettings()

    init { viewModelScope.launch { load() } }

    private suspend fun load() {
        val d = devices.device(deviceId) ?: return _ui.update { it.copy(loading = false, error = "missing") }
        val ds = DeviceSettings.decode(d.customSettings)
        if (ds == null) { _ui.update { it.copy(loading = false, corrupt = true, device = d) }; return }
        deviceSettings = ds
        when (val r = codes.load(d.codeSetId)) {
            is LoadResult.Error -> _ui.update { it.copy(loading = false, error = r.reason, device = d) }
            is LoadResult.Ok -> {
                val set = r.set
                val (caps, temps) = capabilitiesOf(set) ?: return _ui.update { it.copy(loading = false, error = "not_ac", device = d) }
                val defaults = settings.current()
                val initial = ds.acState ?: AcState(power = false, mode = defaults.defaultAcMode, temperatureCelsius = defaults.defaultAcTemp)
                val entity = codes.codeSetEntity(d.codeSetId)
                val protocolId = (set as? LoadedCodeSet.AcProtocolSet)?.protocol?.info?.id ?: entity?.featureMetadata
                _ui.value = AcUiState(
                    loading = false, device = d, set = set, caps = caps, tableTemps = temps,
                    state = coerce(initial, caps, temps), lastSent = ds.lastSentAcState, protocolId = protocolId,
                    protocolLevel = (set as? LoadedCodeSet.AcProtocolSet)?.protocol?.info?.verification,
                    offAt = ds.acOffAt?.takeIf { it > System.currentTimeMillis() },
                )
                devices.markUsed(d.id)
            }
        }
    }

    private fun capabilitiesOf(set: LoadedCodeSet) = AcStateRules.capabilitiesOf(set)

    private fun coerce(s: AcState, caps: AcCapabilities, temps: ((AcMode, FanSpeed) -> List<Int>)?): AcState = AcStateRules.coerce(s, caps, temps)

    fun update(debounce: Boolean = false, change: (AcState) -> AcState) {
        val ui = _ui.value
        val caps = ui.caps ?: return
        val next = coerce(change(ui.state), caps, ui.tableTemps)
        _ui.update { it.copy(state = next) }
        sendJob?.cancel()
        // While the unit is off, mode/temperature/fan edits are only saved: sending would either
        // switch it on unasked or repeat the OFF code. They go out together with the next power-on.
        if (!next.power && !ui.state.power) {
            sendJob = viewModelScope.launch {
                deviceSettings = deviceSettings.copy(acState = next)
                devices.saveSettings(deviceId, deviceSettings)
            }
            return
        }
        sendJob = viewModelScope.launch {
            if (debounce) delay(350)
            send(next)
        }
    }

    private suspend fun send(state: AcState) {
        val set = _ui.value.set ?: return
        _ui.update { it.copy(sending = true) }
        val outcome = remote.sendAc(set, state, _ui.value.lastSent)
        val sent = outcome is SendOutcome.Success
        deviceSettings = deviceSettings.copy(acState = state, lastSentAcState = if (sent) state else deviceSettings.lastSentAcState)
        devices.saveSettings(deviceId, deviceSettings)
        _ui.update { it.copy(sending = false, outcome = outcome, lastSent = deviceSettings.lastSentAcState) }
    }

    fun extra(cmd: AcExtraCommand) = viewModelScope.launch {
        val set = _ui.value.set as? LoadedCodeSet.AcProtocolSet ?: return@launch
        val outcome = remote.sendAcExtra(set, cmd.id, _ui.value.state)
        if (outcome is SendOutcome.Success && set.protocol.capabilities.powerControl == PowerControl.TOGGLE) {
            // A bare toggle flips the assumed power state.
            val flipped = _ui.value.state.copy(power = !(_ui.value.lastSent?.power ?: false))
            deviceSettings = deviceSettings.copy(acState = flipped, lastSentAcState = flipped)
            devices.saveSettings(deviceId, deviceSettings)
            _ui.update { it.copy(state = flipped, lastSent = flipped) }
        }
        _ui.update { it.copy(outcome = outcome) }
    }

    /** Lets the user correct the assumed state without transmitting (after using the physical remote). */
    fun markAssumedPower(on: Boolean) = viewModelScope.launch {
        val s = _ui.value.state.copy(power = on)
        deviceSettings = deviceSettings.copy(acState = s, lastSentAcState = s)
        devices.saveSettings(deviceId, deviceSettings)
        _ui.update { it.copy(state = s, lastSent = s) }
    }

    fun favoriteState(label: String) = viewModelScope.launch {
        devices.addFavoriteCommand(deviceId, label, null, DeviceSettings.json.encodeToString(AcState.serializer(), _ui.value.state))
    }

    fun resetCorrupt() = viewModelScope.launch {
        devices.saveSettings(deviceId, DeviceSettings())
        _ui.value = AcUiState()
        load()
    }

    fun toggleLayout(current: RemoteLayout) = viewModelScope.launch {
        settings.setRemoteLayout(if (current == RemoteLayout.EXPANDED) RemoteLayout.COMPACT else RemoteLayout.EXPANDED)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AcRemoteScreen(onBack: () -> Unit, onDiagnostics: (String?) -> Unit, vm: AcRemoteViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val favLabel = stringResource(R.string.ac_fav_label)
    val favAdded = stringResource(R.string.ac_fav_added)
    LaunchedEffect(ui.outcome) {
        val o = ui.outcome
        if (o != null && o !is SendOutcome.Success) snackbar.showSnackbar(describeOutcome(context, o))
    }

    AppScaffold(
        ui.device?.displayName ?: stringResource(R.string.cat_ac), onBack = onBack, snackbar = snackbar,
        actions = {
            IconButton(onClick = { vm.favoriteState(favLabel); scope.launch { snackbar.showSnackbar(favAdded) } }, enabled = ui.caps != null) {
                Icon(Icons.Filled.StarBorder, stringResource(R.string.ac_fav_state))
            }
            IconButton(onClick = { vm.toggleLayout(settings.remoteLayout) }) {
                Icon(if (settings.remoteLayout == RemoteLayout.EXPANDED) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore, stringResource(R.string.ac_layout_toggle))
            }
        },
    ) { padding ->
        when {
            ui.loading -> LoadingBox(Modifier.padding(padding))
            ui.corrupt -> StatusPanel(Icons.Filled.ErrorOutline, stringResource(R.string.state_corrupt_title), stringResource(R.string.state_corrupt_body),
                Modifier.padding(padding), actionLabel = stringResource(R.string.state_corrupt_action), onAction = vm::resetCorrupt)
            ui.error != null || ui.caps == null -> StatusPanel(Icons.Filled.ErrorOutline, stringResource(R.string.state_unsupported_title),
                stringResource(R.string.state_unsupported_body, ui.error ?: ""), Modifier.padding(padding), actionLabel = stringResource(R.string.action_back), onAction = onBack)
            else -> AcContent(ui, settings.remoteLayout == RemoteLayout.EXPANDED, settings.temperatureUnit, vm, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AcContent(ui: AcUiState, expanded: Boolean, unit: TemperatureUnit, vm: AcRemoteViewModel, modifier: Modifier) {
    val caps = ui.caps!!
    val s = ui.state
    val modeColor by animateColorAsState(if (s.power) acModeColor(s.mode) else MaterialTheme.colorScheme.outline, label = "modeColor")
    val tempsForMode = ui.tableTemps?.invoke(s.mode, s.fanSpeed)
    val ignored = s.mode in caps.temperatureIgnoredIn
    val range = caps.temperatureRange
    val canDown = !ignored && (tempsForMode?.any { it < s.temperatureCelsius } ?: (s.temperatureCelsius > range.first))
    val canUp = !ignored && (tempsForMode?.any { it > s.temperatureCelsius } ?: (s.temperatureCelsius < range.last))
    val step: (Int) -> Unit = { dir ->
        vm.update(debounce = true) { st ->
            val next = if (dir < 0) tempsForMode?.filter { it < st.temperatureCelsius }?.maxOrNull() ?: (st.temperatureCelsius - 1)
            else tempsForMode?.filter { it > st.temperatureCelsius }?.minOrNull() ?: (st.temperatureCelsius + 1)
            st.copy(temperatureCelsius = next)
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).readableWidth(600.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ui.device?.let { com.universalir.feature.remote.RemoteHeader(it) }
        Spacer(Modifier.height(12.dp))

        // Hero: mode-tinted card with the draggable dial and the main controls.
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.background(Brush.verticalGradient(listOf(modeColor.copy(alpha = 0.16f), Color.Transparent)))) {
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(modeColor))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (s.power) stringResource(R.string.ac_power_on) + " · " + stringResource(modeName(s.mode)) else stringResource(R.string.ac_power_off),
                            style = MaterialTheme.typography.titleMedium, color = modeColor,
                        )
                    }
                    if (!s.power) Text(stringResource(R.string.ac_off_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(4.dp))
                    com.universalir.core.ui.CappedFontScale { TemperatureDial(
                        value = s.temperatureCelsius, range = range, allowed = tempsForMode, ignored = ignored, power = s.power, color = modeColor,
                        unit = unit, size = if (expanded) 260.dp else 200.dp,
                        onPick = { t -> vm.update(debounce = true) { it.copy(temperatureCelsius = t) } },
                    ) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        RemoteButton(AppIcons.Minus, stringResource(R.string.ac_temp_down), onClick = { if (canDown) step(-1) })
                        RemoteButton(AppIcons.Power, stringResource(R.string.ac_power), emphasized = s.power, size = 88.dp, onClick = { vm.update { it.copy(power = !it.power) } })
                        RemoteButton(AppIcons.Plus, stringResource(R.string.ac_temp_up), onClick = { if (canUp) step(1) })
                    }
                }
            }
        }

        // Modes as tiles.
        val modes = listOf(AcMode.COOL, AcMode.HEAT, AcMode.AUTO, AcMode.DRY, AcMode.FAN_ONLY).filter { it in caps.modes }
        AcSection(stringResource(R.string.ac_mode_title)) {
            TileRow(modes.size) { i ->
                val m = modes[i]
                AcTile(stringResource(modeName(m)), selected = s.mode == m, color = acModeColor(m), onClick = { vm.update { it.copy(mode = m) } }) {
                    Icon(modeIcon(m), null, Modifier.size(24.dp))
                }
            }
        }

        // Fan speed with bar graphics.
        val fans = listOf(FanSpeed.AUTO, FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH).filter { it in caps.fanSpeedsFor(s.mode) }
        if (fans.isNotEmpty()) AcSection(stringResource(R.string.ac_fan)) {
            TileRow(fans.size) { i ->
                val f = fans[i]
                AcTile(stringResource(fanName(f)), selected = s.fanSpeed == f, color = modeColor, onClick = { vm.update { it.copy(fanSpeed = f) } }) {
                    if (f == FanSpeed.AUTO) Icon(AppIcons.Auto, null, Modifier.size(24.dp)) else FanBars(level = f.ordinal)
                }
            }
        }

        // Features as toggle tiles.
        val features = buildList {
            if (SwingMode.AUTO in caps.verticalSwing) add(Feature(R.string.ac_swing_v, AppIcons.Swing, s.verticalSwing == SwingMode.AUTO) {
                vm.update { it.copy(verticalSwing = if (it.verticalSwing == SwingMode.AUTO) SwingMode.OFF else SwingMode.AUTO) } })
            if (SwingMode.AUTO in caps.horizontalSwing) add(Feature(R.string.ac_swing_h, AppIcons.Swing, s.horizontalSwing == SwingMode.AUTO) {
                vm.update { it.copy(horizontalSwing = if (it.horizontalSwing == SwingMode.AUTO) SwingMode.OFF else SwingMode.AUTO) } })
            if (AcFeature.TURBO in caps.features) add(Feature(R.string.ac_turbo, AppIcons.Wind, s.turbo == true) { vm.update { it.copy(turbo = it.turbo != true) } })
            if (AcFeature.QUIET in caps.features) add(Feature(R.string.ac_quiet, AppIcons.Mute, s.quiet == true) { vm.update { it.copy(quiet = it.quiet != true) } })
            if (AcFeature.SLEEP in caps.features) add(Feature(R.string.ac_sleep, AppIcons.Moon, s.sleep == true) { vm.update { it.copy(sleep = it.sleep != true) } })
            if (AcFeature.ECO in caps.features) add(Feature(R.string.ac_eco, AppIcons.Heart, s.eco == true) { vm.update { it.copy(eco = it.eco != true) } })
            if (AcFeature.LIGHT in caps.features) add(Feature(R.string.ac_light, AppIcons.Bulb, s.light != false) { vm.update { it.copy(light = it.light == false) } })
            if (AcFeature.IFEEL in caps.features) add(Feature(R.string.ac_ifeel, AppIcons.Info, s.iFeel == true) {
                vm.update { it.copy(iFeel = it.iFeel != true, modelSpecificFields = it.modelSpecificFields + (AcState.FIELD_IFEEL_SENSOR_C to it.temperatureCelsius.toString())) }
            })
        }
        if (expanded && features.isNotEmpty()) AcSection(stringResource(R.string.ac_features)) {
            features.chunked(3).forEach { line ->
                TileRow(3) { i ->
                    line.getOrNull(i)?.let { f -> AcTile(stringResource(f.label), f.on, modeColor, f.toggle) { Icon(f.icon, null, Modifier.size(24.dp)) } }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        // One-tap presets, only for modes the unit has.
        val presets = buildList {
            if (AcMode.COOL in caps.modes) add(Preset(R.string.ac_preset_cool, AppIcons.Snowflake, AcMode.COOL, 24, sleep = false))
            if (AcMode.COOL in caps.modes) add(Preset(R.string.ac_preset_sleep, AppIcons.Moon, AcMode.COOL, 26, sleep = true))
            if (AcMode.HEAT in caps.modes) add(Preset(R.string.ac_preset_warm, AppIcons.Sun, AcMode.HEAT, 22, sleep = false))
            if (AcMode.DRY in caps.modes) add(Preset(R.string.ac_preset_dry, AppIcons.Drop, AcMode.DRY, 24, sleep = false))
        }
        if (presets.isNotEmpty()) AcSection(stringResource(R.string.ac_quick)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    Surface(
                        onClick = { vm.update { it.copy(power = true, mode = p.mode, temperatureCelsius = p.temp, sleep = if (AcFeature.SLEEP in caps.features) p.sleep else it.sleep) } },
                        shape = MaterialTheme.shapes.large, color = acModeColor(p.mode).copy(alpha = 0.14f), modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(vertical = 12.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(p.icon, null, tint = acModeColor(p.mode), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(p.label), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            Text(com.universalir.core.ui.formatTemp(p.temp, unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        AcSection(stringResource(R.string.ac_timer)) { OffTimer(ui.offAt, vm::setTimer) }

        if (expanded) caps.extraCommands.forEach { cmd ->
            OutlinedButton(onClick = { vm.extra(cmd) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(extraLabel(cmd)) }
        }
        if (caps.powerControl == PowerControl.TOGGLE) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ac_sync_power), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                FilterChip(s.power, { vm.markAssumedPower(true) }, label = { Text(stringResource(R.string.ac_power_on)) })
                FilterChip(!s.power, { vm.markAssumedPower(false) }, label = { Text(stringResource(R.string.ac_power_off)) })
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

private data class Feature(val label: Int, val icon: ImageVector, val on: Boolean, val toggle: () -> Unit)
private data class Preset(val label: Int, val icon: ImageVector, val mode: AcMode, val temp: Int, val sleep: Boolean)

@Composable
private fun acModeColor(m: AcMode): Color {
    val status = LocalStatusColors.current
    return when (m) {
        AcMode.COOL -> status.cool
        AcMode.HEAT -> status.heat
        AcMode.DRY -> Color(0xFF14B8A6)
        AcMode.FAN_ONLY -> status.success
        AcMode.AUTO -> MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun AcSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
    content()
}

/** [count] equal-width cells in one row. */
@Composable
private fun TileRow(count: Int, cell: @Composable (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) { i -> Box(Modifier.weight(1f)) { cell(i) } }
    }
}

/** Selectable tile: icon over label, filled with [color] when selected. */
@Composable
private fun AcTile(label: String, selected: Boolean, color: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val feedback = rememberFeedback()
    val bg by animateColorAsState(if (selected) color else MaterialTheme.colorScheme.surfaceContainerHigh, label = "tileBg")
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(onClick = { feedback.click(); onClick() }, shape = MaterialTheme.shapes.large, color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 1–3 rising bars for low/medium/high fan speed. */
@Composable
private fun FanBars(level: Int) {
    val c = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width / 5
        for (i in 0 until 3) {
            val h = size.height * (0.4f + 0.3f * i)
            drawRoundRect(
                color = if (i < level) c else c.copy(alpha = 0.25f),
                topLeft = Offset(i * w * 1.8f + w * 0.2f, size.height - h), size = Size(w * 1.2f, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/**
 * Arc dial: shows the target temperature and lets the user drag along the arc to pick one.
 * Captured tables only expose some temperatures, so a drag snaps to the nearest allowed value.
 */
@Composable
private fun TemperatureDial(
    value: Int, range: IntRange, allowed: List<Int>?, ignored: Boolean, power: Boolean, color: Color,
    unit: TemperatureUnit, size: androidx.compose.ui.unit.Dp, onPick: (Int) -> Unit,
) {
    val feedback = rememberFeedback()
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction by animateFloatAsState(if (ignored) 0f else (value - range.first).toFloat() / span, label = "dial")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val knob = MaterialTheme.colorScheme.surface
    var last by remember { mutableStateOf(value) }
    Box(
        Modifier.size(size).pointerInput(range, allowed, ignored) {
            if (ignored) return@pointerInput
            fun pick(p: Offset) {
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                // Angle measured clockwise from the arc start (135°), over a 270° sweep.
                var a = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())) - 135.0
                while (a < 0) a += 360.0
                if (a > 270.0) a = if (a > 315.0) 0.0 else 270.0
                val raw = range.first + Math.round(a / 270.0 * span).toInt()
                val t = allowed?.minByOrNull { kotlin.math.abs(it - raw) } ?: raw.coerceIn(range)
                if (t != last) { last = t; feedback.click(); onPick(t) }
            }
            detectDragGestures(onDragStart = { pick(it) }, onDrag = { change, _ -> change.consume(); pick(change.position) })
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
            val stroke = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = stroke)
            if (!ignored) {
                val sweep = 270f * fraction.coerceIn(0.02f, 1f)
                drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.5f), color, color.copy(alpha = 0.5f))), startAngle = 135f, sweepAngle = sweep, useCenter = false, style = stroke)
                // Knob at the end of the arc.
                val r = (this.size.minDimension / 2f)
                val ang = Math.toRadians((135f + sweep).toDouble())
                val p = Offset(center.x + r * kotlin.math.cos(ang).toFloat(), center.y + r * kotlin.math.sin(ang).toFloat())
                drawCircle(knob, radius = 13.dp.toPx(), center = p)
                drawCircle(color, radius = 8.dp.toPx(), center = p)
            }
        }
        val shown = if (unit == TemperatureUnit.FAHRENHEIT) Math.round(value * 9 / 5f + 32) else value
        val unitLabel = if (unit == TemperatureUnit.FAHRENHEIT) "°F" else "°C"
        AnimatedContent(targetState = shown, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "temp") { t ->
            // Number and unit are one token; keep "24°C" in that order in RTL locales too.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics { contentDescription = "$t $unitLabel" }) {
                    Text(if (ignored) "--" else t.toString(), style = MaterialTheme.typography.displayLarge, color = color)
                    Text(unitLabel, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

fun modeIcon(m: AcMode): ImageVector = when (m) {
    AcMode.AUTO -> AppIcons.Auto
    AcMode.COOL -> AppIcons.Snowflake
    AcMode.HEAT -> AppIcons.Sun
    AcMode.DRY -> AppIcons.Drop
    AcMode.FAN_ONLY -> AppIcons.Wind
}

fun modeName(m: AcMode): Int = when (m) {
    AcMode.AUTO -> R.string.mode_auto
    AcMode.COOL -> R.string.mode_cool
    AcMode.HEAT -> R.string.mode_heat
    AcMode.DRY -> R.string.mode_dry
    AcMode.FAN_ONLY -> R.string.mode_fan
}

fun fanName(f: FanSpeed): Int = when (f) {
    FanSpeed.AUTO -> R.string.fan_auto
    FanSpeed.LOW -> R.string.fan_low
    FanSpeed.MEDIUM -> R.string.fan_medium
    FanSpeed.HIGH -> R.string.fan_high
}

@Composable
private fun extraLabel(cmd: AcExtraCommand): String = when (cmd.id) {
    "power_toggle" -> stringResource(R.string.ac_extra_toggle)
    "swing_toggle" -> stringResource(R.string.ac_extra_swing)
    else -> cmd.label
}

/** Off-timer: preset durations, or the time it will switch off with a cancel button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OffTimer(offAt: Long?, onSet: (Int?) -> Unit) {
    if (offAt != null) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Timer, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(offAt))
                Text(stringResource(R.string.ac_timer_at, time), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                androidx.compose.material3.TextButton(onClick = { onSet(null) }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(30, 60, 120, 240, 480).forEach { m ->
            androidx.compose.material3.AssistChip(onClick = { onSet(m) },
                label = { Text(if (m < 60) stringResource(R.string.timer_min, m) else stringResource(R.string.timer_hours, m / 60)) },
                leadingIcon = { Icon(AppIcons.Timer, null, Modifier.size(18.dp)) })
        }
    }
}
