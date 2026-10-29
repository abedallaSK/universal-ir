package com.universalir.feature.customremote

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.SectionHeader
import com.universalir.core.ui.categoryName
import com.universalir.core.ui.describeOutcome
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.ImportOutcome
import com.universalir.data.repository.LoadResult
import com.universalir.domain.model.CustomButton
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.domain.model.categoryOf
import com.universalir.domain.usecase.RemoteController
import com.universalir.domain.usecase.SendOutcome
import com.universalir.feature.remote.designerIcon
import com.universalir.feature.remote.designerIcons
import com.universalir.ir.core.PatternValidator
import com.universalir.ir.format.BroadlinkCodec
import com.universalir.ir.format.CanonicalCodeSet
import com.universalir.ir.format.CanonicalCommand
import com.universalir.ir.format.CanonicalRenderer
import com.universalir.ir.format.CodeSource
import com.universalir.ir.format.CommandKind
import com.universalir.ir.format.DecodeResult
import com.universalir.ir.format.ProntoCodec
import com.universalir.ir.format.RawTimingsParser
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.consumer.ConsumerProtocols
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject

data class DesignerButton(val id: String, val label: String, val icon: String, val command: CanonicalCommand)

@Serializable
data class CustomRemoteExport(val formatVersion: Int = 1, val name: String, val codeSet: CanonicalCodeSet, val buttons: List<CustomButton>)

data class DesignerState(
    val name: String = "",
    val category: DeviceCategory = DeviceCategory.OTHER,
    val buttons: List<DesignerButton> = emptyList(),
    val editingDeviceId: Long? = null,
    val message: String? = null,
    val testOutcome: SendOutcome? = null,
)

@HiltViewModel
class DesignerViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val devices: DeviceRepository,
    private val codes: CodeRepository,
    private val remote: RemoteController,
) : ViewModel() {
    private val deviceArg: Long = handle["deviceId"] ?: -1L
    private val _state = MutableStateFlow(DesignerState())
    val state = _state.asStateFlow()
    val savedDevices = devices.listDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Commands of the device chosen as a copy source. */
    val sourceCommands = MutableStateFlow<List<CanonicalCommand>>(emptyList())

    init {
        if (deviceArg > 0) viewModelScope.launch {
            val d = devices.device(deviceArg) ?: return@launch
            val ds = DeviceSettings.decode(d.customSettings) ?: DeviceSettings()
            val set = (codes.load(d.codeSetId) as? LoadResult.Ok)?.set as? LoadedCodeSet.CommandSet
            val buttons = if (set != null && ds.customButtons.isNotEmpty()) {
                ds.customButtons.mapNotNull { b -> set.codes.commands.firstOrNull { it.name == b.commandName }?.let { DesignerButton(b.id, b.label, b.icon, it) } }
            } else emptyList()
            _state.value = DesignerState(d.displayName, categoryOf(d.category), buttons, d.id)
            set?.let { sourceCommands.value = it.codes.commands }
        }
    }

    fun setName(n: String) = _state.update { it.copy(name = n) }
    fun setCategory(c: DeviceCategory) = _state.update { it.copy(category = c) }

    fun loadSource(d: SavedDeviceEntity) = viewModelScope.launch {
        sourceCommands.value = when (val r = codes.load(d.codeSetId)) {
            is LoadResult.Ok -> when (val s = r.set) {
                is LoadedCodeSet.CommandSet -> s.codes.commands
                is LoadedCodeSet.AcTableSet -> s.codes.commands
                is LoadedCodeSet.AcProtocolSet -> emptyList()
            }
            is LoadResult.Error -> emptyList()
        }
    }

    fun add(label: String, icon: String, cmd: CanonicalCommand) = _state.update {
        it.copy(buttons = it.buttons + DesignerButton(UUID.randomUUID().toString().take(8), label.ifBlank { cmd.label }, icon, cmd))
    }

    /** Validates a pasted code (raw timings, Pronto or Broadlink) before it can become a button. */
    fun parsePasted(kind: String, text: String, carrierKhz: String): Pair<CanonicalCommand?, String?> {
        val result = when (kind) {
            "pronto" -> ProntoCodec.decode(text)
            "broadlink" -> BroadlinkCodec.decodeBase64(text)
            else -> {
                val khz = carrierKhz.toDoubleOrNull() ?: return null to "carrier"
                RawTimingsParser.parse(text, (khz * 1000).toInt())
            }
        }
        return when (result) {
            is DecodeResult.Error -> null to result.reason
            is DecodeResult.Ok -> {
                val issues = PatternValidator.validate(result.value)
                if (issues.isNotEmpty()) null to issues.joinToString(" ") { it.message }
                else CanonicalCommand("raw", "Raw", CommandKind.RAW, result.value.carrierHz, result.value.timings().toList()) to null
            }
        }
    }

    fun parsedCommand(protocolId: String, addressHex: String, commandHex: String): Pair<CanonicalCommand?, String?> {
        val p = ConsumerProtocols.byId(protocolId) ?: return null to "protocol"
        val a = addressHex.removePrefix("0x").toLongOrNull(16) ?: return null to "address"
        val c = commandHex.removePrefix("0x").toLongOrNull(16) ?: return null to "command"
        val range = p.checkRange(a, c)
        if (range.isNotEmpty()) return null to range.joinToString(" ")
        return CanonicalCommand("parsed", p.info.id, CommandKind.PARSED, protocol = p.info.id, address = a, command = c) to null
    }

    fun test(cmd: CanonicalCommand) = viewModelScope.launch {
        val outcome = remote.send("designer", cmd.name, CanonicalRenderer.render(cmd))
        _state.update { it.copy(testOutcome = outcome) }
    }

    fun move(from: Int, to: Int) = _state.update { s ->
        if (to !in s.buttons.indices) s else s.copy(buttons = s.buttons.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun remove(id: String) = _state.update { s -> s.copy(buttons = s.buttons.filterNot { it.id == id }) }
    fun rename(id: String, label: String) = _state.update { s -> s.copy(buttons = s.buttons.map { if (it.id == id) it.copy(label = label) else it }) }
    fun setIcon(id: String, icon: String) = _state.update { s -> s.copy(buttons = s.buttons.map { if (it.id == id) it.copy(icon = icon) else it }) }

    fun buildExport(): CustomRemoteExport {
        val s = _state.value
        val commands = s.buttons.map { it.command.copy(name = "custom_${it.id}", label = it.label) }
        val set = CanonicalCodeSet(
            id = "user-" + UUID.randomUUID().toString().take(8), protocolId = commands.map { it.protocol ?: "raw" }.distinct().singleOrNull() ?: "mixed",
            category = s.category, brand = "Custom", models = listOf(s.name), commands = commands,
            source = CodeSource("User", "user://custom-remote", license = "USER"),
        )
        return CustomRemoteExport(name = s.name, codeSet = set, buttons = s.buttons.map { CustomButton(it.id, it.label, it.icon, "custom_${it.id}") })
    }

    fun save(onDone: (Long) -> Unit) = viewModelScope.launch {
        val s = _state.value
        if (s.name.isBlank() || s.buttons.isEmpty()) return@launch
        val export = buildExport()
        val outcome = codes.saveCodeSet(export.codeSet, origin = "USER", modelName = s.name)
        val codeSetId = when (outcome) {
            is ImportOutcome.Imported -> outcome.codeSetId
            else -> return@launch _state.update { it.copy(message = (outcome as? ImportOutcome.Invalid)?.reason ?: "duplicate") }
        }
        val settings = DeviceSettings(customButtons = export.buttons)
        val id = s.editingDeviceId?.let { existing ->
            val d = devices.device(existing) ?: return@launch
            devices.updateDevice(d.copy(displayName = s.name, codeSetId = codeSetId, category = s.category.name,
                customSettings = (DeviceSettings.decode(d.customSettings) ?: DeviceSettings()).copy(customButtons = export.buttons, hiddenButtons = emptySet()).encode()))
            existing
        } ?: devices.addDevice(s.name, s.category, "Custom", null, codeSetId, settings = settings)
        onDone(id)
    }
}

@Composable
fun CustomRemoteDesignerScreen(onDone: (Long) -> Unit, onBack: () -> Unit, vm: DesignerViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DesignerButton?>(null) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.testOutcome) { s.testOutcome?.let { snackbar.showSnackbar(describeOutcome(context, it)) } }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val json = CanonicalCodeSet.json.encodeToString(CustomRemoteExport.serializer(), vm.buildExport())
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }
    }

    AppScaffold(stringResource(R.string.nav_designer), onBack = onBack, snackbar = snackbar, actions = {
        IconButton(onClick = { exporter.launch("${s.name.ifBlank { "remote" }}.uir.json") }, enabled = s.buttons.isNotEmpty()) {
            Icon(Icons.Filled.FileDownload, stringResource(R.string.designer_export))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(s.name, vm::setName, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DeviceCategory.entries.filter { it != DeviceCategory.AIR_CONDITIONER }) { c ->
                        FilterChip(s.category == c, { vm.setCategory(c) }, label = { Text(stringResource(categoryName(c))) })
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.designer_buttons)) }
            if (s.buttons.isEmpty()) item {
                Text(stringResource(R.string.designer_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(s.buttons, key = { _, b -> b.id }) { i, b ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { renaming = b }) { designerIcon(b.icon)?.let { Icon(it, null) } ?: Text(b.label.take(2)) }
                        Column(Modifier.weight(1f)) {
                            Text(b.label, style = MaterialTheme.typography.titleSmall)
                            LtrText("${b.command.protocol ?: "raw"} · ${b.command.carrierHz?.let { "${it / 1000} kHz" } ?: ""}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.test(b.command) }) { Icon(Icons.Filled.Send, stringResource(R.string.designer_test)) }
                        IconButton(onClick = { vm.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.action_move_up)) }
                        IconButton(onClick = { vm.move(i, i + 1) }, enabled = i < s.buttons.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.action_move_down)) }
                        IconButton(onClick = { vm.remove(b.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { adding = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.designer_add_button))
                }
            }
            item {
                Button(onClick = { vm.save(onDone) }, Modifier.fillMaxWidth(), enabled = s.name.isNotBlank() && s.buttons.isNotEmpty()) {
                    Text(stringResource(R.string.designer_save))
                }
            }
        }
    }
    if (adding) AddButtonDialog(vm, onDismiss = { adding = false })
    renaming?.let { b -> EditButtonDialog(b, onSave = { label, icon -> vm.rename(b.id, label); vm.setIcon(b.id, icon) }, onDismiss = { renaming = null }) }
}

@Composable
private fun EditButtonDialog(b: DesignerButton, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var label by rememberSaveable { mutableStateOf(b.label) }
    var icon by rememberSaveable { mutableStateOf(b.icon) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.designer_edit_button)) }, text = {
        Column {
            OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.designer_label)) }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            IconPicker(icon) { icon = it }
        }
    }, confirmButton = { TextButton(onClick = { onSave(label, icon); onDismiss() }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun IconPicker(selected: String, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        item { FilterChip(selected == "text", { onPick("text") }, label = { Text("Aa") }) }
        items(designerIcons.keys.toList()) { k ->
            IconButton(onClick = { onPick(k) }) {
                Icon(designerIcons.getValue(k), k, tint = if (selected == k) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddButtonDialog(vm: DesignerViewModel, onDismiss: () -> Unit) {
    var source by rememberSaveable { mutableStateOf("device") }
    var label by rememberSaveable { mutableStateOf("") }
    var icon by rememberSaveable { mutableStateOf("text") }
    var text by rememberSaveable { mutableStateOf("") }
    var carrier by rememberSaveable { mutableStateOf("38") }
    var protocol by rememberSaveable { mutableStateOf("NEC") }
    var address by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<CanonicalCommand?>(null) }
    val devices by vm.savedDevices.collectAsStateWithLifecycle()
    val sourceCommands by vm.sourceCommands.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.designer_add_button)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("device" to R.string.designer_src_device, "protocol" to R.string.designer_src_protocol, "raw" to R.string.designer_src_raw,
                        "pronto" to R.string.designer_src_pronto, "broadlink" to R.string.designer_src_broadlink)) { (k, res) ->
                        FilterChip(source == k, { source = k; error = null; picked = null }, label = { Text(stringResource(res)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.designer_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                IconPicker(icon) { icon = it }
                when (source) {
                    "device" -> {
                        Text(stringResource(R.string.designer_pick_device), style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(devices, key = { it.id }) { d -> FilterChip(false, { vm.loadSource(d) }, label = { Text(d.displayName) }) }
                        }
                        sourceCommands.take(200).forEach { c ->
                            TextButton(onClick = { picked = c; if (label.isBlank()) label = c.label }) {
                                LtrText((if (picked == c) "✓ " else "") + c.label)
                            }
                        }
                    }
                    "protocol" -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(ConsumerProtocols.all.map { it.info.id }) { p -> FilterChip(protocol == p, { protocol = p }, label = { LtrText(p) }) }
                        }
                        OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.designer_address_hex)) }, singleLine = true)
                        OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.designer_command_hex)) }, singleLine = true)
                    }
                    else -> {
                        if (source == "raw") OutlinedTextField(carrier, { carrier = it }, label = { Text(stringResource(R.string.designer_carrier_khz)) }, singleLine = true)
                        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.designer_paste_code)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.designer_raw_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error?.let { Text(stringResource(R.string.designer_invalid, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (cmd, err) = when (source) {
                    "device" -> picked to (if (picked == null) "-" else null)
                    "protocol" -> vm.parsedCommand(protocol, address, command)
                    else -> vm.parsePasted(source, text, carrier)
                }
                if (cmd == null) error = err ?: "-" else { vm.add(label, icon, cmd); onDismiss() }
            }) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
