package com.universalir.feature.database

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.ui.AppCard
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.ConfirmDialog
import com.universalir.core.ui.KeyValueRow
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.categoryName
import com.universalir.data.catalog.CatalogSeeder
import com.universalir.data.local.AppDatabase
import com.universalir.data.repository.CodePack
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.ImportOutcome
import com.universalir.data.repository.ImportRequest
import com.universalir.data.repository.SettingsRepository
import com.universalir.data.repository.UpdateDownloader
import com.universalir.data.repository.UpdateResult
import com.universalir.ir.format.CanonicalCodeSet
import com.universalir.ir.protocol.DeviceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DbMessage {
    data class Imported(val accepted: Int, val rejected: List<String>) : DbMessage
    data class Duplicate(val title: String) : DbMessage
    data class Invalid(val reason: String, val rejected: List<String>) : DbMessage
    data class Removed(val count: Int) : DbMessage
    data class Update(val result: UpdateResult) : DbMessage
    data object CacheCleared : DbMessage
    data class Exported(val count: Int) : DbMessage
}

@HiltViewModel
class DatabaseViewModel @Inject constructor(
    private val codes: CodeRepository,
    private val seeder: CatalogSeeder,
    private val settings: SettingsRepository,
    private val downloader: UpdateDownloader,
) : ViewModel() {
    val counts = codes.counts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val catalog = runCatching { seeder.readCatalog() }.getOrNull()
    val prefs = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val message = MutableStateFlow<DbMessage?>(null)
    val busy = MutableStateFlow(false)
    val progressBytes = MutableStateFlow(0L)
    private var job: Job? = null

    fun import(req: ImportRequest) {
        job = viewModelScope.launch {
            busy.value = true
            message.value = if (req.text.contains("\"codeSets\"")) importPack(req.text) else when (val r = codes.importFile(req)) {
                is ImportOutcome.Imported -> DbMessage.Imported(r.accepted, r.rejected)
                is ImportOutcome.Duplicate -> DbMessage.Duplicate(r.existingTitle)
                is ImportOutcome.Invalid -> DbMessage.Invalid(r.reason, r.rejected)
            }
            busy.value = false
        }
    }

    private suspend fun importPack(text: String): DbMessage {
        val pack = runCatching { CanonicalCodeSet.json.decodeFromString(CodePack.serializer(), text) }.getOrElse { return DbMessage.Invalid(it.message ?: "", emptyList()) }
        var n = 0
        pack.codeSets.take(500).forEach { set ->
            val single = CanonicalCodeSet.json.encodeToString(CanonicalCodeSet.serializer(), set)
            val r = com.universalir.ir.format.CanonicalImporter.import(single).codeSet ?: return@forEach
            if (codes.saveCodeSet(r, "IMPORTED", r.models.firstOrNull() ?: "Imported") is ImportOutcome.Imported) n++
        }
        return DbMessage.Imported(n, emptyList())
    }

    fun cancel() { job?.cancel(); busy.value = false }

    fun exportJson(onReady: (String) -> Unit) = viewModelScope.launch {
        val sets = codes.exportUserCodeSets()
        onReady(CanonicalCodeSet.json.encodeToString(CodePack.serializer(), CodePack(name = "Universal IR user export", codeSets = sets)))
        message.value = DbMessage.Exported(sets.size)
    }

    fun clearCache() { codes.clearCache(); message.value = DbMessage.CacheCleared }

    fun removeUnverified() = viewModelScope.launch { message.value = DbMessage.Removed(codes.removeUnverifiedImported()) }

    fun reseed() = viewModelScope.launch {
        busy.value = true
        settings.setCatalogSeedVersion(0)
        seeder.seedIfNeeded()
        busy.value = false
    }

    fun setUrl(u: String) = viewModelScope.launch { settings.setUpdateUrl(u) }

    fun download(url: String) {
        job = viewModelScope.launch {
            busy.value = true; progressBytes.value = 0
            message.value = DbMessage.Update(downloader.download(url) { progressBytes.value = it })
            busy.value = false
        }
    }
}

private const val MAX_IMPORT_BYTES = 2_000_000

@Composable
fun DatabaseScreen(onBack: () -> Unit, vm: DatabaseViewModel = hiltViewModel()) {
    val counts by vm.counts.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.progressBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var url by rememberSaveable(prefs?.updateUrl) { mutableStateOf(prefs?.updateUrl ?: "") }
    val tooLarge = stringResource(R.string.db_file_too_large)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "file"
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(MAX_IMPORT_BYTES + 1) } }.getOrNull()
        when {
            bytes == null -> fileError = "?"
            bytes.size > MAX_IMPORT_BYTES -> fileError = tooLarge
            else -> pendingFile = name to bytes.toString(Charsets.UTF_8)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportJson { json -> context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
    }

    AppScaffold(stringResource(R.string.nav_database), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (counts == null && vm.catalog == null) item {
                StatusPanel(Icons.Filled.ErrorOutline, stringResource(R.string.state_db_title), stringResource(R.string.state_db_body),
                    actionLabel = stringResource(R.string.db_reseed), onAction = vm::reseed)
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.db_status), style = MaterialTheme.typography.titleSmall)
                    KeyValueRow(stringResource(R.string.db_schema), AppDatabase.VERSION.toString())
                    KeyValueRow(stringResource(R.string.db_catalog_version), vm.catalog?.version?.toString() ?: "—")
                    counts?.let {
                        KeyValueRow(stringResource(R.string.db_brands), it.brands.toString())
                        KeyValueRow(stringResource(R.string.db_models), it.models.toString())
                        KeyValueRow(stringResource(R.string.db_code_sets), it.codeSets.toString())
                        KeyValueRow(stringResource(R.string.db_imported), it.imported.toString())
                        KeyValueRow(stringResource(R.string.db_user), it.user.toString())
                    }
                    Text(stringResource(R.string.db_offline_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = vm::reseed, enabled = !busy) { Text(stringResource(R.string.db_reseed)) }
                }
            }
            if (busy) item {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (progress > 0) Text(stringResource(R.string.db_downloaded, progress / 1024), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::cancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            message?.let { m -> item { MessageCard(m) } }
            fileError?.let { item { Text(stringResource(R.string.state_invalid_file_body, it), color = MaterialTheme.colorScheme.error) } }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.db_import_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.db_import_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { fileError = null; picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.db_import_file)) }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.db_manage), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { exporter.launch("universal-ir-export.json") }) { Text(stringResource(R.string.db_export)) }
                        OutlinedButton(onClick = vm::clearCache) { Text(stringResource(R.string.db_clear_cache)) }
                    }
                    OutlinedButton(onClick = { confirmRemove = true }, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.db_remove_unverified)) }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.db_updates), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.db_updates_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text(stringResource(R.string.db_update_url)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { vm.setUrl(url) }) { Text(stringResource(R.string.action_save)) }
                        Button(onClick = { vm.setUrl(url); vm.download(url) }, enabled = url.startsWith("https://") && !busy) { Text(stringResource(R.string.db_check_updates)) }
                    }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.db_licenses), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.db_licenses_body), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    pendingFile?.let { (name, text) ->
        ImportDialog(name, onImport = { brand, model, cat, license ->
            vm.import(ImportRequest(text, name, brand, model, cat, license)); pendingFile = null
        }, onDismiss = { pendingFile = null })
    }
    if (confirmRemove) ConfirmDialog(stringResource(R.string.db_remove_unverified), stringResource(R.string.db_remove_body), stringResource(R.string.action_delete),
        vm::removeUnverified, { confirmRemove = false }, destructive = true)
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun MessageCard(m: DbMessage) {
    val text = when (m) {
        is DbMessage.Imported -> stringResource(R.string.db_msg_imported, m.accepted, m.rejected.size)
        is DbMessage.Duplicate -> stringResource(R.string.db_msg_duplicate, m.title)
        is DbMessage.Invalid -> stringResource(R.string.state_invalid_file_body, m.reason)
        is DbMessage.Removed -> stringResource(R.string.db_msg_removed, m.count)
        DbMessage.CacheCleared -> stringResource(R.string.db_msg_cache)
        is DbMessage.Exported -> stringResource(R.string.db_msg_exported, m.count)
        is DbMessage.Update -> when (val r = m.result) {
            is UpdateResult.Done -> stringResource(R.string.db_msg_update, r.imported, r.duplicates, r.rejected)
            is UpdateResult.Failed -> stringResource(R.string.db_msg_update_failed, r.reason)
        }
    }
    val rejected = (m as? DbMessage.Imported)?.rejected ?: (m as? DbMessage.Invalid)?.rejected ?: emptyList()
    AppCard(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        rejected.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (rejected.size > 20) Text("… +${rejected.size - 20}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ImportDialog(fileName: String, onImport: (String, String, DeviceCategory, String) -> Unit, onDismiss: () -> Unit) {
    var brand by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(fileName.substringBeforeLast('.')) }
    var license by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf(DeviceCategory.TELEVISION) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.db_import_title)) },
        text = {
            Column {
                Text(fileName, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(brand, { brand = it }, label = { Text(stringResource(R.string.field_brand)) }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.field_model)) }, singleLine = true)
                OutlinedTextField(license, { license = it }, label = { Text(stringResource(R.string.field_license)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.field_license_hint)) })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DeviceCategory.entries.toList()) { c -> FilterChip(cat == c, { cat = c }, label = { Text(stringResource(categoryName(c))) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(brand.ifBlank { "Unknown" }, model, cat, license.ifBlank { "Unknown (user-supplied)" }) }) { Text(stringResource(R.string.db_import_file)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
