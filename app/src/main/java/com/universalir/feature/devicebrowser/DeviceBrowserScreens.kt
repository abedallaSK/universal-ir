package com.universalir.feature.devicebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.Pill
import com.universalir.core.ui.SectionHeader
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.VerificationBadge
import com.universalir.core.ui.categoryIcon
import com.universalir.core.ui.categoryName
import com.universalir.core.ui.verificationDescription
import com.universalir.data.local.BrandEntity
import com.universalir.data.local.RoomEntity
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.SettingsRepository
import com.universalir.domain.model.CodeSetSummary
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.usecase.CodeTester
import com.universalir.domain.usecase.PrepareResult
import com.universalir.domain.usecase.PreparedTest
import com.universalir.domain.usecase.SendOutcome
import com.universalir.core.ui.describeOutcome
import kotlinx.coroutines.flow.update
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.universalir.domain.model.categoryOf
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.ProtocolRegistry
import com.universalir.ir.protocol.VerificationLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val ANY_BRAND = "__any__"

// ------------------------------------------------------------------ Page 5: Add device

@Composable
fun AddDeviceScreen(onCategory: (DeviceCategory) -> Unit, onCustom: () -> Unit, onImport: () -> Unit, onWifi: () -> Unit, onBack: () -> Unit) {
    AppScaffold(stringResource(R.string.add_title), onBack = onBack) { padding ->
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp), Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(DeviceCategory.entries.toList()) { cat ->
                AddTile(com.universalir.core.ui.lookOf(cat, null), stringResource(categoryName(cat))) { onCategory(cat) }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.set_more), Modifier.padding(top = 8.dp)) }
            item { AddTile(com.universalir.core.ui.DeviceLook(com.universalir.core.ui.AppIcons.Wifi, com.universalir.core.ui.deviceGradients[5], "wifi", 5), stringResource(R.string.nav_wifi), onWifi) }
            item { AddTile(com.universalir.core.ui.DeviceLook(Icons.Filled.Dashboard, com.universalir.core.ui.deviceGradients[1], "custom", 1), stringResource(R.string.add_custom), onCustom) }
            item { AddTile(com.universalir.core.ui.DeviceLook(Icons.Filled.FileOpen, com.universalir.core.ui.deviceGradients[7], "import", 7), stringResource(R.string.add_import), onImport) }
        }
    }
}

@Composable
private fun AddTile(look: com.universalir.core.ui.DeviceLook, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 22.dp, horizontal = 12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            com.universalir.core.ui.DeviceAvatar(look, size = 60.dp)
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

// ------------------------------------------------------------------ Page 6: Brand selection

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrandSelectionViewModel @Inject constructor(
    handle: SavedStateHandle,
    codes: CodeRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val category: DeviceCategory = categoryOf(handle["category"])
    val query = MutableStateFlow("")
    val brands = query.flatMapLatest { codes.searchBrands(it, category) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val recent = combine(settings.settings.map { it.recentBrands }, codes.getBrands(category)) { ids, all ->
        ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun remember(brandId: String) = viewModelScope.launch { if (brandId != ANY_BRAND) settings.addRecentBrand(brandId) }
}

@Composable
fun BrandSelectionScreen(onBrand: (category: String, brandId: String) -> Unit, onBack: () -> Unit, vm: BrandSelectionViewModel = hiltViewModel()) {
    val brands by vm.brands.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val pick: (String) -> Unit = { id -> vm.remember(id); onBrand(vm.category.name, id) }

    AppScaffold(stringResource(R.string.brand_title, stringResource(categoryName(vm.category))), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(
                    query, { vm.query.value = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true,
                    placeholder = { Text(stringResource(R.string.brand_search_hint)) },
                )
            }
            if (query.isBlank()) {
                val popular = brands.orEmpty().filter { it.popular }
                if (recent.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.brand_recent)) }
                    item { ChipRow(recent, pick) }
                }
                if (popular.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.brand_popular)) }
                    item { ChipRow(popular, pick) }
                }
                item { SectionHeader(stringResource(R.string.brand_all)) }
            }
            val list = brands
            if (list != null && list.isEmpty()) {
                item {
                    StatusPanel(Icons.Filled.SearchOff, stringResource(R.string.state_no_codes_title), stringResource(R.string.brand_none_body),
                        actionLabel = stringResource(R.string.brand_unknown), onAction = { pick(ANY_BRAND) })
                }
            }
            items(list.orEmpty(), key = { it.id }) { b ->
                Surface(onClick = { pick(b.id) }, color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(b.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (b.popular) Pill(stringResource(R.string.brand_popular_tag), MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = { pick(ANY_BRAND) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.brand_unknown))
                    }
                    Text(stringResource(R.string.brand_unknown_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ChipRow(brands: List<BrandEntity>, onPick: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(brands, key = { it.id }) { b -> AssistChip(onClick = { onPick(b.id) }, label = { Text(b.name) }) }
    }
}

// ------------------------------------------------------------------ Page 7: Model selection

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ModelSelectionViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val codes: CodeRepository,
    private val devices: DeviceRepository,
    private val tester: CodeTester,
) : ViewModel() {
    val category: DeviceCategory = categoryOf(handle["category"])
    val brandId: String = handle["brandId"] ?: ANY_BRAND
    val brandName = MutableStateFlow("")
    val query = MutableStateFlow("")
    val selectedModel = MutableStateFlow<String?>(null)
    val protocolFilter = MutableStateFlow<String?>(null)
    val rooms = devices.rooms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val brandArg get() = brandId.takeIf { it != ANY_BRAND }

    val models = query.flatMapLatest { q ->
        if (brandArg != null) codes.getModels(brandArg!!, category, q) else codes.searchDevices(q.ifBlank { "" }, category)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val codeSets = combine(selectedModel, protocolFilter) { m, p -> m to p }
        .flatMapLatest { (m, p) -> codes.getCodeSets(m, brandArg, category, p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch { brandName.value = brandArg?.let { codes.brand(it)?.name } ?: "" }
    }

    fun save(summary: CodeSetSummary, name: String, roomId: Long?, onSaved: (Long) -> Unit) = viewModelScope.launch {
        // A successful trial seeds the AC remote with the state that was just sent.
        val tested = trial.value?.takeIf { it.summary.codeSetId == summary.codeSetId }?.test?.acState
        val settings = DeviceSettings(acState = tested, lastSentAcState = tested)
        val id = devices.addDevice(name, category, summary.brandName, summary.modelId, summary.codeSetId, roomId, settings)
        trial.value = null
        onSaved(id)
    }

    // ---------------------------------------------------------------- Test before adding

    val trial = MutableStateFlow<TrialState?>(null)

    /** Opens the trial for [summary] and sends its test command once (the user pressed Test). */
    fun startTrial(summary: CodeSetSummary) = viewModelScope.launch {
        trial.value = TrialState(summary)
        when (val r = tester.prepare(summary.codeSetId)) {
            is PrepareResult.Error -> trial.update { it?.copy(error = r.reason) }
            is PrepareResult.Ready -> {
                trial.update { it?.copy(test = r.test) }
                sendTrial()
            }
        }
    }

    fun sendTrial() = viewModelScope.launch {
        val t = trial.value?.test ?: return@launch
        if (trial.value?.sending == true) return@launch
        trial.update { it?.copy(sending = true) }
        val outcome = tester.send(t)
        trial.update { s ->
            if (outcome == null) s?.copy(sending = false)
            else s?.copy(sending = false, outcome = outcome, awaitingAnswer = outcome is SendOutcome.Success)
        }
    }

    fun turnAcOffAfterTrial() = viewModelScope.launch { trial.value?.test?.let { tester.turnAcOff(it) } }

    /** Records the answer. "No" moves to the next code set in the list but does not send it yet. */
    fun answerTrial(worked: Boolean, onWorked: (CodeSetSummary) -> Unit) = viewModelScope.launch {
        val s = trial.value ?: return@launch
        if (s.outcome is SendOutcome.Success) {
            codes.recordVerificationResult(s.summary.codeSetId, s.summary.modelId, s.test?.label ?: "test", if (worked) "WORKED" else "NO_RESPONSE")
        }
        if (worked) {
            trial.update { it?.copy(awaitingAnswer = false) }
            onWorked(s.summary)
            return@launch
        }
        val list = codeSets.value.orEmpty().distinctBy { it.codeSetId }
        val next = list.getOrNull(list.indexOfFirst { it.codeSetId == s.summary.codeSetId } + 1)
        if (next == null) {
            trial.update { it?.copy(awaitingAnswer = false, noMore = true) }
            return@launch
        }
        trial.value = TrialState(next)
        when (val r = tester.prepare(next.codeSetId)) {
            is PrepareResult.Error -> trial.update { it?.copy(error = r.reason) }
            is PrepareResult.Ready -> trial.update { it?.copy(test = r.test) }
        }
    }

    fun closeTrial() { trial.value = null }
}

data class TrialState(
    val summary: CodeSetSummary,
    val test: PreparedTest? = null,
    val error: String? = null,
    val sending: Boolean = false,
    val outcome: SendOutcome? = null,
    val awaitingAnswer: Boolean = false,
    val noMore: Boolean = false,
)

@Composable
fun ModelSelectionScreen(
    onSaved: (Long) -> Unit,
    onWizard: (category: String, brandId: String, modelId: String?) -> Unit,
    onCodeSet: (String) -> Unit,
    onBack: () -> Unit,
    vm: ModelSelectionViewModel = hiltViewModel(),
) {
    val models by vm.models.collectAsStateWithLifecycle()
    val codeSets by vm.codeSets.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selected by vm.selectedModel.collectAsStateWithLifecycle()
    val protocol by vm.protocolFilter.collectAsStateWithLifecycle()
    val brandName by vm.brandName.collectAsStateWithLifecycle()
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<CodeSetSummary?>(null) }
    var warned by remember { mutableStateOf(false) }
    val trial by vm.trial.collectAsStateWithLifecycle()

    val title = if (vm.brandId == ANY_BRAND) stringResource(R.string.model_title_any) else stringResource(R.string.model_title, brandName)
    AppScaffold(title, onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                OutlinedTextField(
                    query, { vm.query.value = it; vm.selectedModel.value = null }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true,
                    placeholder = { Text(stringResource(R.string.model_search_hint)) },
                )
            }
            item {
                Surface(onClick = { onWizard(vm.category.name, vm.brandId, selected) }, shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoFixHigh, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.wizard_cta_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(stringResource(R.string.wizard_cta_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.model_models)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected == null, { vm.selectedModel.value = null }, label = { Text(stringResource(R.string.model_all)) }) }
                    items(models.orEmpty(), key = { it.modelId }) { m ->
                        FilterChip(selected == m.modelId, { vm.selectedModel.value = if (selected == m.modelId) null else m.modelId },
                            label = { LtrText(if (vm.brandId == ANY_BRAND) "${m.brandName} ${m.modelNumber}" else m.modelNumber, maxLines = 1) })
                    }
                }
            }
            item {
                val acProtocols = ProtocolRegistry.acProtocols.map { it.info.id }
                if (vm.category == DeviceCategory.AIR_CONDITIONER) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(acProtocols + "smartir_table") { p ->
                            FilterChip(protocol == p, { vm.protocolFilter.value = if (protocol == p) null else p }, label = { LtrText(p, maxLines = 1) })
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.model_code_sets)) }
            val sets = codeSets
            if (sets != null && sets.isEmpty()) {
                item {
                    StatusPanel(Icons.Filled.SearchOff, stringResource(R.string.state_no_codes_title), stringResource(R.string.state_no_codes_body),
                        actionLabel = stringResource(R.string.add_import), onAction = onBack)
                }
            }
            items(sets.orEmpty(), key = { it.codeSetId + it.modelId }) { s ->
                CodeSetCard(s, onClick = { pending = s; warned = false }, onInfo = { onCodeSet(s.codeSetId) }, onTest = { vm.startTrial(s) })
            }
        }
    }

    trial?.let { t ->
        if (pending == null) {
            val list = codeSets.orEmpty().distinctBy { it.codeSetId }
            TrialDialog(
                t, position = list.indexOfFirst { it.codeSetId == t.summary.codeSetId } + 1, total = list.size,
                isAc = vm.category == DeviceCategory.AIR_CONDITIONER,
                onSend = { vm.sendTrial() },
                // The user just confirmed it works on their device, so no unverified warning.
                onYes = { vm.answerTrial(true) { worked -> pending = worked; warned = true } },
                onNo = { vm.answerTrial(false) {} },
                onTurnOff = { vm.turnAcOffAfterTrial() },
                onClose = vm::closeTrial,
            )
        }
    }

    pending?.let { s ->
        val needsWarning = false
        if (needsWarning) {
            UnverifiedWarning(s, onContinue = { warned = true }, onDismiss = { pending = null })
        } else {
            SaveDeviceDialog(
                defaultName = listOf(s.brandName, stringResource(categoryName(vm.category))).joinToString(" "),
                rooms = rooms,
                onSave = { name, room -> vm.save(s, name, room, onSaved); pending = null },
                onDismiss = { pending = null },
            )
        }
    }
}

@Composable
fun CodeSetCard(s: CodeSetSummary, onClick: () -> Unit, onInfo: () -> Unit, onTest: (() -> Unit)? = null) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.brandName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LtrText(s.remoteModel?.takeIf { it.isNotBlank() } ?: s.modelNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                TextButton(onClick = onInfo) { Icon(Icons.Filled.Info, stringResource(R.string.action_details)) }
            }
            if (onTest != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTest) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.trial_test))
                    }
                    Button(onClick = onClick) { Text(stringResource(R.string.trial_use)) }
                }
            }
        }
    }
}

/** Test-before-adding: send one safe command, then the user says whether the device reacted. */
@Composable
fun TrialDialog(
    t: TrialState, position: Int, total: Int, isAc: Boolean,
    onSend: () -> Unit, onYes: () -> Unit, onNo: () -> Unit, onTurnOff: () -> Unit, onClose: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.trial_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (total > 0 && position > 0) Text(stringResource(R.string.wizard_progress, position, total), style = MaterialTheme.typography.labelMedium)
                Text(t.summary.brandName, style = MaterialTheme.typography.titleSmall)
                LtrText(t.summary.modelNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                t.test?.let { Text(stringResource(R.string.wizard_test_command, it.label), style = MaterialTheme.typography.bodyMedium) }
                t.error?.let { Text(stringResource(R.string.wizard_load_error, it), color = MaterialTheme.colorScheme.error) }
                t.outcome?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(describeOutcome(context, it), style = MaterialTheme.typography.bodySmall,
                        color = if (it is SendOutcome.Success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
                if (t.noMore) Text(stringResource(R.string.trial_none_left), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                when {
                    t.awaitingAnswer -> {
                        Text(stringResource(R.string.wizard_question), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onYes, Modifier.fillMaxWidth()) { Text(stringResource(R.string.trial_yes_save)) }
                        OutlinedButton(onClick = onNo, Modifier.fillMaxWidth()) { Text(stringResource(R.string.trial_no_next)) }
                        TextButton(onClick = onSend, Modifier.fillMaxWidth(), enabled = !t.sending) { Text(stringResource(R.string.action_retry)) }
                        if (isAc && t.test?.acState != null) TextButton(onClick = onTurnOff, Modifier.fillMaxWidth()) { Text(stringResource(R.string.wizard_turn_off)) }
                    }
                    t.noMore -> {}
                    t.error != null -> OutlinedButton(onClick = onNo, Modifier.fillMaxWidth()) { Text(stringResource(R.string.trial_skip_next)) }
                    else -> {
                        Button(onClick = onSend, Modifier.fillMaxWidth(), enabled = t.test != null && !t.sending) { Text(stringResource(R.string.wizard_send_test)) }
                        OutlinedButton(onClick = onNo, Modifier.fillMaxWidth()) { Text(stringResource(R.string.trial_skip_next)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
fun UnverifiedWarning(s: CodeSetSummary, onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null, tint = LocalStatusColors.current.warning) },
        title = { Text(stringResource(R.string.unverified_title)) },
        text = {
            Column {
                Text(stringResource(R.string.unverified_body))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(verificationDescription(s.verification)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.unverified_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun SaveDeviceDialog(defaultName: String, rooms: List<RoomEntity>, onSave: (String, Long?) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var room by rememberSaveable { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_device_title)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (rooms.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.field_room), style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(room == null, { room = null }, label = { Text(stringResource(R.string.rooms_none)) }) }
                        items(rooms, key = { it.id }) { r -> FilterChip(room == r.id, { room = r.id }, label = { Text(r.name) }) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(name, room) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
