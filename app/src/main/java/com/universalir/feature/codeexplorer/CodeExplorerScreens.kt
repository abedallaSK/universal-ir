package com.universalir.feature.codeexplorer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.ui.AppCard
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.KeyValueRow
import com.universalir.core.ui.LoadingBox
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.SectionHeader
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.VerificationBadge
import com.universalir.core.ui.categoryName
import com.universalir.core.ui.verificationDescription
import com.universalir.data.local.VerificationEntity
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.LoadResult
import com.universalir.domain.model.CodeSetSummary
import com.universalir.domain.model.LicensePolicy
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.feature.devicebrowser.CodeSetCard
import com.universalir.ir.format.CanonicalCodeSet
import com.universalir.ir.format.CommandKind
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.ProtocolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CodeExplorerViewModel @Inject constructor(private val codes: CodeRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val category = MutableStateFlow<DeviceCategory?>(null)
    val protocol = MutableStateFlow<String?>(null)
    val protocols = codes.protocols().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Brand/model search first, then the matching code sets (bounded result size). */
    val results = combine(query, category, protocol) { q, c, p -> Triple(q, c, p) }.flatMapLatest { (q, c, p) ->
        if (q.isBlank()) codes.getCodeSets(null, null, c, p)
        else codes.searchDevices(q, c, limit = 40).flatMapLatest { hits ->
            if (hits.isEmpty()) flowOf(emptyList())
            else combine(hits.map { h -> codes.getCodeSets(h.modelId, null, c, p) }) { arrays -> arrays.flatMap { it.toList() } }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun CodeExplorerScreen(onCodeSet: (String) -> Unit, onBack: () -> Unit, vm: CodeExplorerViewModel = hiltViewModel()) {
    val q by vm.query.collectAsStateWithLifecycle()
    val cat by vm.category.collectAsStateWithLifecycle()
    val proto by vm.protocol.collectAsStateWithLifecycle()
    val protocols by vm.protocols.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()

    AppScaffold(stringResource(R.string.nav_explorer), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(q, { vm.query.value = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true,
                    placeholder = { Text(stringResource(R.string.explorer_search_hint)) })
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(cat == null, { vm.category.value = null }, label = { Text(stringResource(R.string.model_all)) }) }
                    items(DeviceCategory.entries.toList()) { c -> FilterChip(cat == c, { vm.category.value = if (cat == c) null else c }, label = { Text(stringResource(categoryName(c))) }) }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(protocols) { p -> FilterChip(proto == p, { vm.protocol.value = if (proto == p) null else p }, label = { LtrText(p) }) }
                }
            }
            val list = results?.distinctBy { it.codeSetId + it.modelId }
            if (list != null && list.isEmpty()) item {
                StatusPanel(Icons.Filled.SearchOff, stringResource(R.string.state_no_codes_title), stringResource(R.string.explorer_none))
            }
            items(list.orEmpty().take(300), key = { it.codeSetId + it.modelId }) { s -> CodeSetCard(s, onClick = { onCodeSet(s.codeSetId) }, onInfo = { onCodeSet(s.codeSetId) }) }
        }
    }
}

data class DetailState(
    val summary: CodeSetSummary? = null,
    val profiles: List<CodeSetSummary> = emptyList(),
    val loaded: LoadedCodeSet? = null,
    val error: String? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class CodeSetDetailViewModel @Inject constructor(handle: SavedStateHandle, private val codes: CodeRepository) : ViewModel() {
    val id: String = handle["codeSetId"] ?: ""
    val state = MutableStateFlow(DetailState())
    val verifications = codes.verifications(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val entity = codes.codeSetEntity(id)
            val profiles = codes.getCodeSets(null, null, null, entity?.protocolId).first().filter { it.codeSetId == id }
            val r = codes.load(id)
            state.value = DetailState(profiles.firstOrNull(), profiles, (r as? LoadResult.Ok)?.set, (r as? LoadResult.Error)?.reason, false)
        }
    }

    fun exportJson(): String? {
        val set = when (val l = state.value.loaded) {
            is LoadedCodeSet.CommandSet -> l.codes
            is LoadedCodeSet.AcTableSet -> l.codes
            else -> null
        } ?: return null
        return CanonicalCodeSet.json.encodeToString(CanonicalCodeSet.serializer(), set)
    }
}

@Composable
fun CodeSetDetailScreen(onBack: () -> Unit, onProtocol: (String) -> Unit, vm: CodeSetDetailViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val verifications by vm.verifications.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var rawFor by remember { mutableStateOf<String?>(null) }
    val license = s.summary?.license ?: ""
    val exportAllowed = LicensePolicy.allowsExport(license)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = vm.exportJson()
        if (uri != null && json != null) context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
    }

    AppScaffold(s.summary?.title ?: stringResource(R.string.explorer_detail), onBack = onBack, actions = {
        if (exportAllowed && s.loaded !is LoadedCodeSet.AcProtocolSet && s.loaded != null) {
            IconButton(onClick = { exporter.launch("${vm.id.replace(':', '_')}.uir.json") }) { Icon(Icons.Filled.FileDownload, stringResource(R.string.explorer_export)) }
        }
    }) { padding ->
        if (s.loading) { LoadingBox(Modifier.padding(padding)); return@AppScaffold }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.summary?.let { sum ->
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        VerificationBadge(sum.verification)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(verificationDescription(sum.verification)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        KeyValueRow(stringResource(R.string.detail_category), stringResource(categoryName(sum.category)))
                        KeyValueRow(stringResource(R.string.detail_protocol), sum.protocolId, ltrValue = true)
                        KeyValueRow(stringResource(R.string.detail_source), sum.sourceRepository)
                        KeyValueRow(stringResource(R.string.detail_url), sum.sourceUrl, ltrValue = true)
                        KeyValueRow(stringResource(R.string.detail_license), sum.license, ltrValue = true)
                        sum.remoteModel?.let { KeyValueRow(stringResource(R.string.detail_remote_model), it, ltrValue = true) }
                        sum.evidence?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (ProtocolRegistry.info(sum.protocolId) != null) TextButton(onClick = { onProtocol(sum.protocolId) }) { Text(stringResource(R.string.detail_protocol_info)) }
                    }
                }
                if (s.profiles.size > 1) item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.detail_models), style = MaterialTheme.typography.titleSmall)
                        s.profiles.forEach { LtrText("${it.brandName} · ${it.modelNumber}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (verifications.isNotEmpty()) item { VerificationHistory(verifications) }
            if (s.error != null) item {
                StatusPanel(Icons.Filled.ErrorOutline, stringResource(R.string.state_db_title), s.error ?: "")
            }
            when (val l = s.loaded) {
                is LoadedCodeSet.AcProtocolSet -> item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.detail_generated), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        LtrText(l.protocol.info.frameDescription, style = MaterialTheme.typography.bodySmall)
                    }
                }
                is LoadedCodeSet.CommandSet, is LoadedCodeSet.AcTableSet -> {
                    val cmds = if (l is LoadedCodeSet.CommandSet) l.codes.commands else (l as LoadedCodeSet.AcTableSet).codes.commands
                    item { SectionHeader(stringResource(R.string.detail_commands, cmds.size)) }
                    if (!exportAllowed) item {
                        Row { Icon(Icons.Filled.Lock, null); Text(stringResource(R.string.detail_export_blocked, license), style = MaterialTheme.typography.bodySmall) }
                    }
                    items(cmds.take(500), key = { it.name }) { c ->
                        Surface(onClick = { rawFor = if (rawFor == c.name) null else c.name }, color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                LtrText(c.label, style = MaterialTheme.typography.titleSmall)
                                LtrText(
                                    if (c.kind == CommandKind.RAW) "raw · ${c.carrierHz ?: "?"} Hz · ${c.timings?.size ?: 0} timings"
                                    else "${c.protocol} · addr 0x${c.address?.toString(16)} · cmd 0x${c.command?.toString(16)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (rawFor == c.name) RawPanel(context, c.timings?.joinToString(" ") ?: "${c.protocol} ${c.address} ${c.command}", exportAllowed)
                            }
                        }
                    }
                }
                null -> {}
            }
        }
    }
}

@Composable
private fun RawPanel(context: Context, text: String, copyAllowed: Boolean) {
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider()
        SelectionContainer {
            LtrText(text, Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
        if (copyAllowed) TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ir", text))
        }) { Icon(Icons.Filled.ContentCopy, null); Text(stringResource(R.string.action_copy)) }
    }
}

@Composable
private fun VerificationHistory(list: List<VerificationEntity>) {
    AppCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.detail_verifications), style = MaterialTheme.typography.titleSmall)
        list.take(10).forEach { v ->
            val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(v.timestamp))
            Text("$date · ${v.testedFunction} · ${v.result}", style = MaterialTheme.typography.bodySmall)
            LtrText(v.testedPhone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
