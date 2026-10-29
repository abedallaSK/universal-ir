package com.universalir.feature.wifi

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.AppCard
import com.universalir.core.ui.AppIcons
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.ConfirmDialog
import com.universalir.core.ui.DeviceAvatar
import com.universalir.core.ui.DeviceLook
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.Pill
import com.universalir.core.ui.SectionHeader
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.TextInputDialog
import com.universalir.core.ui.deviceGradients
import com.universalir.data.wifi.DiscoveredDevice
import com.universalir.data.wifi.LanMath
import com.universalir.data.wifi.ManualKind
import com.universalir.data.wifi.SavedWifiDevice
import com.universalir.data.wifi.WifiDeviceKind
import com.universalir.data.wifi.WifiDeviceStore
import com.universalir.data.wifi.WifiDiscovery
import com.universalir.data.wifi.WifiError
import com.universalir.data.wifi.WifiResult
import com.universalir.data.wifi.toSaved
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Nav routes for the Wi-Fi feature. */
object WifiRoutes {
    const val DISCOVERY = "wifi"
    const val REMOTE = "wifi/remote/{wifiId}"
    fun remote(id: String) = "wifi/remote/${Uri.encode(id)}"
}

data class WifiDiscoveryUi(
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val onLan: Boolean = true,
    val results: List<DiscoveredDevice> = emptyList(),
    val saved: List<SavedWifiDevice> = emptyList(),
)

@HiltViewModel
class WifiDiscoveryViewModel @Inject constructor(
    private val discovery: WifiDiscovery,
    private val store: WifiDeviceStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(WifiDiscoveryUi())
    val ui = _ui.asStateFlow()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch { store.devices.collect { list -> _ui.update { it.copy(saved = list) } } }
        scan()
    }

    fun scan() {
        scanJob?.cancel()
        _ui.update { it.copy(scanning = true, results = emptyList(), onLan = discovery.onLan()) }
        scanJob = viewModelScope.launch {
            discovery.scan().collect { list -> _ui.update { it.copy(results = list) } }
            _ui.update { it.copy(scanning = false, scanned = true) }
            // DHCP may have moved a saved device: refresh its IP when the same id shows up elsewhere.
            val saved = _ui.value.saved.associateBy { it.id }
            _ui.value.results.filter { d -> saved[d.id]?.let { it.ip != d.ip } == true }.forEach { store.upsert(it.toSaved()) }
        }
    }

    /** Saves (or refreshes) a discovered device and returns its id for navigation. */
    suspend fun save(d: DiscoveredDevice): String { store.upsert(d.toSaved()); return d.id }

    /** "Add by IP": checks the address for the chosen type and saves it. Returns the new id. */
    suspend fun addByIp(kind: ManualKind, ip: String, name: String): WifiResult<String> =
        when (val r = discovery.probe(kind, ip)) {
            is WifiResult.Err -> r
            is WifiResult.Ok -> {
                val d = r.value.let { if (name.isNotBlank()) it.copy(name = name.trim()) else it }
                store.putAll(listOf(d.toSaved()))
                WifiResult.Ok(d.id)
            }
        }

    fun rename(id: String, name: String) = viewModelScope.launch { store.rename(id, name) }
    fun remove(id: String) = viewModelScope.launch { store.remove(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDiscoveryScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: WifiDiscoveryViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var actionsFor by remember { mutableStateOf<SavedWifiDevice?>(null) }
    var renaming by remember { mutableStateOf<SavedWifiDevice?>(null) }
    var removing by remember { mutableStateOf<SavedWifiDevice?>(null) }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    var addIp by rememberSaveable { mutableStateOf(false) }
    var addAccount by rememberSaveable { mutableStateOf(false) }

    AppScaffold(WifiText.title, onBack = onBack, snackbar = snackbar, actions = {
        IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, WifiText.add) }
        if (!ui.scanning) IconButton(onClick = vm::scan) { Icon(Icons.Filled.Refresh, WifiText.scanAgain) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Wifi, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(WifiText.sameNetwork, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!ui.onLan) item {
                AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WifiOff, null, tint = LocalStatusColors.current.warning)
                        Spacer(Modifier.width(12.dp))
                        Text(WifiText.notOnWifi, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (ui.saved.isNotEmpty()) {
                item { SectionHeader(WifiText.saved) }
                items(ui.saved, key = { "s-" + it.id }) { d -> SavedRow(d, onOpen = { onOpen(d.id) }, onLongPress = { actionsFor = d }) }
            }
            item {
                SectionHeader(WifiText.found) {
                    if (ui.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            when {
                ui.scanning && ui.results.isEmpty() -> item { Radar() }
                !ui.scanning && ui.results.isEmpty() -> item {
                    StatusPanel(AppIcons.Wifi, WifiText.noneTitle, WifiText.noneTips, actionLabel = WifiText.scanAgain, onAction = vm::scan,
                        secondaryLabel = WifiText.addByIp, onSecondary = { addIp = true })
                }
                else -> {
                    if (ui.scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
                    items(ui.results, key = { "r-" + it.ip }) { d ->
                        FoundRow(d) {
                            if (d.protocol.controllable) scope.launch { onOpen(vm.save(d)) }
                            else scope.launch { snackbar.showSnackbar(WifiText.unsupported) }
                        }
                    }
                    if (!ui.scanning) item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Button(onClick = vm::scan) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(WifiText.scanAgain)
                            }
                        }
                    }
                }
            }
        }
    }

    if (addMenu) {
        ModalBottomSheet(onDismissRequest = { addMenu = false }) {
            Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
                SheetAction(AppIcons.Wifi, WifiText.addByIp, WifiText.addByIpHint) { addMenu = false; addIp = true }
                SheetAction(AppIcons.AirConditioner, WifiText.addByAccount, WifiText.addByAccountHint) { addMenu = false; addAccount = true }
            }
        }
    }
    if (addIp) {
        AddByIpDialog(
            onDismiss = { addIp = false },
            onAdd = { kind, ip, name -> vm.addByIp(kind, ip, name) },
            onAdded = { id -> addIp = false; onOpen(id) },
        )
    }
    if (addAccount) {
        AddAcAccountSheet(onDismiss = { addAccount = false }, onAdded = { n ->
            addAccount = false
            scope.launch { snackbar.showSnackbar(WifiText.addedCount(n)) }
        })
    }
    actionsFor?.let { d ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindBadge(d.kind, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SheetAction(AppIcons.Remote, WifiText.open, null) { actionsFor = null; onOpen(d.id) }
                SheetAction(Icons.Filled.Edit, WifiText.rename, null) { actionsFor = null; renaming = d }
                SheetAction(Icons.Filled.Delete, WifiText.remove, null, destructive = true) { actionsFor = null; removing = d }
            }
        }
    }
    renaming?.let { d ->
        TextInputDialog(WifiText.rename, d.name, WifiText.name, onConfirm = { vm.rename(d.id, it) }, onDismiss = { renaming = null })
    }
    removing?.let { d ->
        ConfirmDialog(WifiText.remove, WifiText.removeConfirm + "\n" + d.name, WifiText.remove, onConfirm = { vm.remove(d.id) }, onDismiss = { removing = null }, destructive = true)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String?, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = color) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        leadingContent = { Icon(icon, null, tint = if (destructive) color else MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private val manualKinds = listOf(ManualKind.ANDROID_TV, ManualKind.SAMSUNG, ManualKind.LG, ManualKind.ROKU, ManualKind.GREE, ManualKind.DAIKIN)

private fun manualLabel(k: ManualKind) = when (k) {
    ManualKind.ANDROID_TV -> WifiText.kind(WifiDeviceKind.TV_ANDROID)
    ManualKind.SAMSUNG -> WifiText.kind(WifiDeviceKind.TV_SAMSUNG)
    ManualKind.LG -> WifiText.kind(WifiDeviceKind.TV_LG)
    ManualKind.ROKU -> "Roku"
    ManualKind.GREE -> "Gree / Tadiran"
    ManualKind.DAIKIN -> "Daikin"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddByIpDialog(onDismiss: () -> Unit, onAdd: suspend (ManualKind, String, String) -> WifiResult<String>, onAdded: (String) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(ManualKind.ANDROID_TV) }
    var ip by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = LanMath.isPrivateIpv4(ip.trim())

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(WifiText.addByIp) },
        text = {
            Column {
                Text(WifiText.deviceType, style = MaterialTheme.typography.labelLarge)
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    manualKinds.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k; error = null }, label = { Text(manualLabel(k)) }, enabled = !busy) }
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(ip, { ip = it.filter { c -> c.isDigit() || c == '.' }.take(15); error = null }, label = { Text(WifiText.ipAddress) },
                        placeholder = { Text(WifiText.ipExample) }, singleLine = true, enabled = !busy,
                        isError = ip.isNotBlank() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it.take(40) }, label = { Text(WifiText.name) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                val msg = error ?: if (ip.isNotBlank() && !valid) WifiText.invalidIp else null
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                if (busy) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(WifiText.checking, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    when (val r = onAdd(kind, ip.trim(), name)) {
                        is WifiResult.Ok -> onAdded(r.value)
                        is WifiResult.Err -> error = if (r.error == WifiError.TIMEOUT || r.error == WifiError.UNREACHABLE || r.error == WifiError.PROTOCOL) WifiText.notFoundAtIp
                            else WifiText.error(r.error, r.detail)
                    }
                    busy = false
                }
            }) { Text(WifiText.add) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(WifiText.cancel) } },
    )
}

@Composable
private fun Radar() {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "radar")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "radarProgress")
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val maxR = size.minDimension / 2f
                repeat(3) { i ->
                    val p = (progress + i / 3f) % 1f
                    drawCircle(color.copy(alpha = (1f - p) * 0.10f), radius = maxR * p)
                    drawCircle(color.copy(alpha = (1f - p) * 0.45f), radius = maxR * p, style = Stroke(width = 2.dp.toPx()))
                }
            }
            Box(Modifier.size(72.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Wifi, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(WifiText.scanning, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(WifiText.scanningHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

internal fun kindIcon(k: WifiDeviceKind): ImageVector = when (k) {
    WifiDeviceKind.TV_SAMSUNG, WifiDeviceKind.TV_LG, WifiDeviceKind.TV_ANDROID -> AppIcons.Television
    WifiDeviceKind.TV_ROKU, WifiDeviceKind.CAST -> AppIcons.TvBox
    WifiDeviceKind.AC_GREE, WifiDeviceKind.AC -> AppIcons.AirConditioner
    WifiDeviceKind.OTHER -> AppIcons.Wifi
}

/** Same gradients as IR devices of the same category (ocean for ACs, violet for TVs, indigo for boxes). */
@Composable
internal fun KindBadge(kind: WifiDeviceKind, size: Dp = 44.dp) {
    val index = when {
        kind.isAc -> 0
        kind == WifiDeviceKind.CAST || kind == WifiDeviceKind.TV_ROKU -> 2
        kind.isTv -> 1
        else -> 7
    }
    DeviceAvatar(DeviceLook(kindIcon(kind), deviceGradients[index], kind.name, index), size = size)
}

@Composable
private fun DeviceRowContent(kind: WifiDeviceKind, name: String, subtitle: String, address: String?, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KindBadge(kind)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!address.isNullOrBlank()) LtrText(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing()
    }
}

private fun subtitleOf(kind: WifiDeviceKind, manufacturer: String?, model: String?): String =
    listOfNotNull(WifiText.kind(kind), listOfNotNull(manufacturer, model).distinct().joinToString(" ").takeIf { it.isNotBlank() }).joinToString(" · ")

@Composable
private fun FoundRow(d: DiscoveredDevice, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = onClick) {
        DeviceRowContent(d.kind, d.name, subtitleOf(d.kind, d.manufacturer, d.model), d.ip) {
            if (d.protocol.controllable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Pill(WifiText.viewOnly, MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Tap opens the remote; long-press opens the device options sheet (same pattern as IR device tiles). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedRow(d: SavedWifiDevice, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onOpen, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() })) {
        Box(Modifier.padding(16.dp)) {
            val subtitle = if (d.protocol.cloud) WifiText.accountLine(d.manufacturer ?: WifiText.kind(d.kind), d.account) else subtitleOf(d.kind, d.manufacturer, d.model)
            DeviceRowContent(d.kind, d.name, subtitle, if (d.protocol.cloud) null else d.ip) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
