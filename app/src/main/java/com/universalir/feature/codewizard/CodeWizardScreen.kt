package com.universalir.feature.codewizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.VerificationBadge
import com.universalir.core.ui.categoryName
import com.universalir.core.ui.describeOutcome
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.DeviceRepository
import com.universalir.domain.model.CodeSetSummary
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.domain.model.categoryOf
import com.universalir.domain.usecase.CodeTester
import com.universalir.domain.usecase.PrepareResult
import com.universalir.domain.usecase.PreparedTest
import com.universalir.domain.usecase.SendOutcome
import com.universalir.feature.devicebrowser.ANY_BRAND
import com.universalir.feature.devicebrowser.SaveDeviceDialog
import com.universalir.feature.devicebrowser.TestPanel
import com.universalir.feature.devicebrowser.testKeyChoices
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.ac.AcState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WizardState(
    val loading: Boolean = true,
    val candidates: List<CodeSetSummary> = emptyList(),
    val index: Int = 0,
    val loaded: LoadedCodeSet? = null,
    val testLabel: String? = null,
    /** Test key in use and the keys the user can switch to (command sets only). */
    val testKey: String? = null,
    val testKeys: List<Pair<String, String>> = emptyList(),
    val loadError: String? = null,
    val sending: Boolean = false,
    val awaitingAnswer: Boolean = false,
    val lastOutcome: SendOutcome? = null,
    val found: CodeSetSummary? = null,
    val testedState: AcState? = null,
    val finished: Boolean = false,
)

@HiltViewModel
class CodeWizardViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val codes: CodeRepository,
    private val devices: DeviceRepository,
    private val tester: CodeTester,
) : ViewModel() {
    val category: DeviceCategory = categoryOf(handle["category"])
    private val brandId: String? = (handle.get<String>("brandId") ?: ANY_BRAND).takeIf { it != ANY_BRAND }
    private val modelId: String? = handle["modelId"]
    private val _state = MutableStateFlow(WizardState())
    val state = _state.asStateFlow()
    val rooms = devices.rooms()
    private var prepared: PreparedTest? = null

    init {
        viewModelScope.launch {
            val list = codes.getCodeSets(modelId, brandId, category).first().distinctBy { it.codeSetId }
            _state.value = WizardState(loading = false, candidates = list, finished = list.isEmpty())
            if (list.isNotEmpty()) loadCandidate(0)
        }
    }

    private suspend fun loadCandidate(i: Int) {
        val c = _state.value.candidates.getOrNull(i) ?: run { _state.update { it.copy(finished = true) }; return }
        prepared = null
        _state.update { it.copy(index = i, loaded = null, loadError = null, awaitingAnswer = false, lastOutcome = null, testLabel = null, testedState = null, testKey = null, testKeys = emptyList()) }
        when (val r = tester.prepare(c.codeSetId)) {
            is PrepareResult.Ready -> {
                prepared = r.test
                val set = r.test.set as? LoadedCodeSet.CommandSet
                val keys = set?.let { cs -> testKeyChoices(cs.commandNames) { n -> cs.codes.commands.firstOrNull { it.name == n }?.label ?: n } }.orEmpty()
                _state.update { it.copy(loaded = r.test.set, testLabel = labelOf(r.test), testKey = r.test.command, testKeys = keys) }
            }
            is PrepareResult.Error -> _state.update { it.copy(loadError = r.reason) }
        }
    }

    fun sendTest() {
        val test = prepared ?: return
        if (_state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            val outcome = tester.send(test)
            _state.update {
                if (outcome == null) it.copy(sending = false)
                else it.copy(sending = false, lastOutcome = outcome, awaitingAnswer = outcome is SendOutcome.Success, testedState = test.acState)
            }
        }
    }

    fun pickKey(command: String) {
        val t = prepared?.copy(command = command) ?: return
        prepared = t
        _state.update { it.copy(testKey = command, testLabel = labelOf(t), awaitingAnswer = false, lastOutcome = null) }
    }

    private fun labelOf(t: PreparedTest): String =
        t.command?.let { c -> (t.set as? LoadedCodeSet.CommandSet)?.codes?.commands?.firstOrNull { it.name == c }?.label ?: c } ?: t.label

    /** Lets the user undo an AC test by sending the matching off state. */
    fun turnAcOff() = viewModelScope.launch { prepared?.let { tester.turnAcOff(it) } }

    fun answer(worked: Boolean) = viewModelScope.launch {
        val s = _state.value
        val c = s.candidates.getOrNull(s.index) ?: return@launch
        codes.recordVerificationResult(c.codeSetId, c.modelId, s.testLabel ?: "test", if (worked) "WORKED" else "NO_RESPONSE")
        if (worked) _state.update { it.copy(found = c, awaitingAnswer = false) } else loadCandidate(s.index + 1)
    }

    fun skip() = viewModelScope.launch { loadCandidate(_state.value.index + 1) }

    fun save(name: String, roomId: Long?, onSaved: (Long) -> Unit) = viewModelScope.launch {
        val c = _state.value.found ?: return@launch
        val tested = _state.value.testedState
        val settings = DeviceSettings(acState = tested, lastSentAcState = tested)
        onSaved(devices.addDevice(name, category, c.brandName, c.modelId, c.codeSetId, roomId, settings))
    }
}

@Composable
fun CodeWizardScreen(onSaved: (Long) -> Unit, onBack: () -> Unit, vm: CodeWizardViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val rooms by vm.rooms.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }

    AppScaffold(stringResource(R.string.wizard_title), onBack = onBack) { padding ->
        if (s.loading) { LoadingBox(Modifier.padding(padding)); return@AppScaffold }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (s.finished && s.found == null) {
                StatusPanel(Icons.Filled.SearchOff, stringResource(R.string.wizard_none_title), stringResource(R.string.wizard_none_body),
                    actionLabel = stringResource(R.string.action_back), onAction = onBack)
                return@Column
            }
            Text(stringResource(R.string.wizard_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { (s.index + 1f) / s.candidates.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.wizard_progress, s.index + 1, s.candidates.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))

            s.found?.let { found ->
                AppCard {
                    Icon(Icons.Filled.CheckCircle, null, tint = LocalStatusColors.current.success, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.wizard_found_title), style = MaterialTheme.typography.titleLarge)
                    Text(found.title, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { saving = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.wizard_save)) }
                    if (s.testedState != null) OutlinedButton(onClick = { vm.turnAcOff() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.wizard_turn_off)) }
                }
                return@Column
            }

            val c = s.candidates.getOrNull(s.index) ?: return@Column
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SettingsRemote, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium)
                        LtrText("${c.brandName} · ${c.modelNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                s.loadError?.let { Text(stringResource(R.string.wizard_load_error, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                Spacer(Modifier.height(16.dp))
                TestPanel(
                    keyLabel = s.testLabel, keys = s.testKeys, selectedKey = s.testKey, onPickKey = vm::pickKey,
                    canSend = s.loaded != null, sending = s.sending, awaiting = s.awaitingAnswer, outcome = s.lastOutcome,
                    onSend = vm::sendTest, onYes = { vm.answer(true) }, onNo = { vm.answer(false) },
                    yesLabel = stringResource(R.string.test_yes), noLabel = stringResource(R.string.test_no_next),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.skip() }, Modifier.fillMaxWidth()) {
                Text(stringResource(if (s.index + 1 < s.candidates.size) R.string.action_skip else R.string.wizard_finish))
            }
        }
    }
    if (saving) {
        val name = listOfNotNull(s.found?.brandName, stringResource(categoryName(vm.category))).joinToString(" ")
        SaveDeviceDialog(name, rooms, onSave = { n, r -> saving = false; vm.save(n, r, onSaved) }, onDismiss = { saving = false })
    }
}
