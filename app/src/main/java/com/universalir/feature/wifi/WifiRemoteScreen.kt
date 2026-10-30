package com.universalir.feature.wifi

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.core.theme.LocalRemoteMetrics
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.AppIcons
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.LoadingBox
import com.universalir.core.ui.LocalAppSettings
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.RemoteButton
import com.universalir.core.ui.StatusPanel
import com.universalir.data.repository.TemperatureUnit
import com.universalir.data.wifi.AndroidTvController
import com.universalir.data.wifi.AndroidTvPairing
import com.universalir.data.wifi.AtvPairingProtocol
import com.universalir.data.wifi.SavedWifiDevice
import com.universalir.data.wifi.TvApp
import com.universalir.data.wifi.TvController
import com.universalir.data.wifi.TvKey
import com.universalir.data.wifi.TvStatus
import com.universalir.data.wifi.WifiAcController
import com.universalir.data.wifi.WifiAcMode
import com.universalir.data.wifi.WifiAcRules
import com.universalir.data.wifi.WifiAcState
import com.universalir.data.wifi.WifiAcStatus
import com.universalir.data.wifi.WifiControllers
import com.universalir.data.wifi.WifiDeviceKind
import com.universalir.data.wifi.WifiDeviceStore
import com.universalir.data.wifi.WifiError
import com.universalir.data.wifi.WifiFan
import com.universalir.data.wifi.WifiProtocol
import com.universalir.data.wifi.WifiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface WifiConn {
    data object Connecting : WifiConn
    data object Pairing : WifiConn
    data object Connected : WifiConn
    /** [reachable] = the device answered but the connection failed for another reason (not "offline"). */
    data class Offline(val error: WifiError, val detail: String? = null, val reachable: Boolean = false) : WifiConn
}

/** Android TV code pairing, shown in place of the remote until the TV accepts this phone. */
sealed interface AtvPairingUi {
    data object Starting : AtvPairingUi
    data class EnterCode(val error: String? = null) : AtvPairingUi
    data object Verifying : AtvPairingUi
    data class Failed(val message: String) : AtvPairingUi
}

data class WifiRemoteUi(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val device: SavedWifiDevice? = null,
    val conn: WifiConn = WifiConn.Connecting,
    val supported: Set<TvKey> = emptySet(),
    val apps: Set<TvApp> = emptySet(),
    val tvStatus: TvStatus? = null,
    val ac: WifiAcStatus? = null,
    val pairing: AtvPairingUi? = null,
    val message: String? = null,
    val messageId: Long = 0,
)

@HiltViewModel
class WifiRemoteViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val store: WifiDeviceStore,
    private val controllers: WifiControllers,
    private val discovery: com.universalir.data.wifi.WifiDiscovery,
) : ViewModel() {
    private val wifiId: String = handle["wifiId"] ?: ""
    private val _ui = MutableStateFlow(WifiRemoteUi())
    val ui = _ui.asStateFlow()
    private var tv: TvController? = null
    private var ac: WifiAcController? = null
    private var pairingSession: AndroidTvPairing? = null
    private var connectJob: Job? = null
    private var acJob: Job? = null
    private var pairJob: Job? = null

    init {
        viewModelScope.launch {
            val d = store.device(wifiId) ?: return@launch _ui.update { it.copy(loading = false, missing = true) }
            tv = controllers.tv(d); ac = controllers.ac(d)
            _ui.update { it.copy(loading = false, device = d, supported = tv?.supported.orEmpty(), apps = tv?.apps.orEmpty()) }
            if (tv == null && ac == null) return@launch _ui.update { it.copy(conn = WifiConn.Offline(WifiError.UNSUPPORTED)) }
            tv?.status?.let { flow -> launch { flow.collect { s -> _ui.update { it.copy(tvStatus = s) } } } }
            connect()
            // The AC can also be changed by its own remote or app: re-read the real state periodically.
            if (ac != null) while (isActive) {
                delay(if (d.protocol.cloud) 30_000 else 15_000)
                if (_ui.value.conn == WifiConn.Connected && acJob?.isActive != true) refreshAc()
            }
        }
    }

    fun connect() {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _ui.update { it.copy(conn = WifiConn.Connecting) }
            tv?.let { c ->
                var r = c.connect { _ui.update { it.copy(conn = WifiConn.Pairing) } }
                // Not answering at the saved address: it may have a new IP from the router. Find it and retry once.
                if (r.isUnreachable()) {
                    relocate()
                    r = tv!!.connect { _ui.update { it.copy(conn = WifiConn.Pairing) } }
                }
                _ui.update { it.copy(conn = r.toConn()) }
                if (r is WifiResult.Err && r.error == WifiError.NOT_PAIRED && c is AndroidTvController && _ui.value.pairing == null) startPairing()
                if (r is WifiResult.Ok) _ui.update { it.copy(pairing = null) }
            }
            if (ac != null) refreshAc(allowRelocate = true)
        }
    }

    private fun WifiResult<*>.isUnreachable() = this is WifiResult.Err && (error == WifiError.UNREACHABLE || error == WifiError.TIMEOUT)

    /**
     * Scans the LAN for the saved device (same MAC, else same kind and name) and, if it now has another IP,
     * stores the new address and rebuilds the controllers. Returns true when it moved.
     */
    private suspend fun relocate(): Boolean {
        val d = _ui.value.device ?: return false
        if (d.protocol.cloud || !discovery.onLan()) return false
        _ui.update { it.copy(conn = WifiConn.Connecting) }
        var found: com.universalir.data.wifi.DiscoveredDevice? = null
        kotlinx.coroutines.withTimeoutOrNull(7_000) {
            discovery.scan(6_000).collect { list ->
                found = list.firstOrNull { !d.mac.isNullOrBlank() && it.mac.equals(d.mac, ignoreCase = true) }
                    ?: list.firstOrNull { it.kind == d.kind && it.protocol == d.protocol && it.name.equals(d.name, ignoreCase = true) }
                if (found != null) throw kotlinx.coroutines.CancellationException("found")
            }
        }
        val f = found ?: return false
        if (f.ip == d.ip) return false
        store.update(d.id) { it.copy(ip = f.ip, port = if (f.port != 0) f.port else it.port) }
        val moved = store.device(d.id) ?: return false
        tv?.close(); ac?.close()
        tv = controllers.tv(moved); ac = controllers.ac(moved)
        _ui.update { it.copy(device = moved) }
        return true
    }

    fun press(key: TvKey) {
        val c = tv ?: return
        viewModelScope.launch { onResult(c.press(key)) }
    }

    fun launchApp(app: TvApp) {
        val c = tv ?: return
        viewModelScope.launch { onResult(c.launch(app)) }
    }

    private fun onResult(r: WifiResult<Unit>) {
        when (r) {
            is WifiResult.Ok -> _ui.update { it.copy(conn = WifiConn.Connected) }
            is WifiResult.Err -> {
                if (r.error == WifiError.NOT_PAIRED && tv is AndroidTvController) { _ui.update { it.copy(conn = r.toConn()) }; startPairing(); return }
                report(r, offline = r.error == WifiError.UNREACHABLE || r.error == WifiError.TIMEOUT)
            }
        }
    }

    // ---------------------------------------------------------------- Android TV pairing

    fun startPairing() {
        val d = _ui.value.device ?: return
        pairJob?.cancel()
        pairingSession?.close()
        val p = controllers.androidTvPairing(d).also { pairingSession = it }
        pairJob = viewModelScope.launch {
            _ui.update { it.copy(pairing = AtvPairingUi.Starting) }
            val r = p.start()
            _ui.update { it.copy(pairing = if (r is WifiResult.Err) AtvPairingUi.Failed(pairingError(r)) else AtvPairingUi.EnterCode()) }
        }
    }

    fun submitCode(code: String) {
        val p = pairingSession ?: return startPairing()
        val d = _ui.value.device ?: return
        pairJob?.cancel()
        pairJob = viewModelScope.launch {
            _ui.update { it.copy(pairing = AtvPairingUi.Verifying) }
            when (val r = p.finish(code)) {
                is WifiResult.Ok -> {
                    controllers.markAndroidTvPaired(d.id)
                    (tv as? AndroidTvController)?.markPaired()
                    p.close(); pairingSession = null
                    _ui.update { it.copy(pairing = null, device = d.copy(paired = true)) }
                    connect()
                }
                is WifiResult.Err -> _ui.update {
                    it.copy(pairing = when {
                        r.error == WifiError.AUTH && p.isOpen -> AtvPairingUi.EnterCode(WifiText.wrongCode)
                        r.error == WifiError.AUTH -> AtvPairingUi.Failed(WifiText.wrongCodeRestart)
                        else -> AtvPairingUi.Failed(pairingError(r))
                    })
                }
            }
        }
    }

    private fun pairingError(e: WifiResult.Err) = when (e.error) {
        WifiError.TIMEOUT, WifiError.UNREACHABLE -> WifiText.tvNoAnswer
        else -> WifiText.error(e.error, e.detail)
    }

    // ---------------------------------------------------------------- AC

    fun togglePower() = acUpdate { it.copy(power = !it.power) }
    fun setMode(m: WifiAcMode) = acUpdate { it.copy(mode = m, power = true) }
    fun setFan(f: WifiFan) = acUpdate { it.copy(fan = f) }
    fun toggleSwing() = acUpdate { it.copy(swing = !it.swing) }
    fun changeTemp(delta: Int) = acUpdate(debounceMs = 450) { it.copy(targetC = it.targetC + delta) }

    /** Optimistic update, send the complete state, then re-read the unit so the screen ends on the real state. */
    private fun acUpdate(debounceMs: Long = 0, change: (WifiAcState) -> WifiAcState) {
        val c = ac ?: return
        val cur = _ui.value.ac ?: return
        val next = WifiAcRules.coerce(change(cur.state), cur.caps)
        _ui.update { it.copy(ac = cur.copy(state = next)) }
        acJob?.cancel()
        acJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val target = _ui.value.ac?.state ?: return@launch
            when (val r = c.write(target)) {
                is WifiResult.Ok -> {
                    _ui.update { it.copy(conn = WifiConn.Connected) }
                    // Cloud services apply the command asynchronously; give them a moment before reading back.
                    delay(if (_ui.value.device?.protocol?.cloud == true) 2500 else 400)
                }
                is WifiResult.Err -> report(r, offline = r.error == WifiError.UNREACHABLE || r.error == WifiError.TIMEOUT)
            }
            refreshAc()
        }
    }

    private suspend fun refreshAc(allowRelocate: Boolean = false) {
        var c = ac ?: return
        var r = c.read()
        if (allowRelocate && r.isUnreachable() && relocate()) { c = ac ?: return; r = c.read() }
        when (r) {
            is WifiResult.Ok -> _ui.update { it.copy(ac = r.value, conn = WifiConn.Connected) }
            is WifiResult.Err -> _ui.update { it.copy(conn = r.toConn()) }
        }
    }

    private fun report(e: WifiResult.Err, offline: Boolean) = _ui.update {
        it.copy(message = errorText(it.device, e.error, e.detail), messageId = it.messageId + 1, conn = if (offline) WifiConn.Offline(e.error, e.detail) else it.conn)
    }

    private fun WifiResult<*>.toConn(): WifiConn = when (this) {
        is WifiResult.Ok -> WifiConn.Connected
        is WifiResult.Err -> WifiConn.Offline(error, detail, reachable = error != WifiError.UNREACHABLE && error != WifiError.TIMEOUT)
    }

    override fun onCleared() { tv?.close(); ac?.close(); pairingSession?.close() }
}

/** Error text, worded for cloud services and Daikin adapters where that's clearer. */
internal fun errorText(d: SavedWifiDevice?, e: WifiError, detail: String?): String = when {
    d?.protocol?.cloud == true && (e == WifiError.UNREACHABLE || e == WifiError.TIMEOUT) -> WifiText.cloudUnreachable
    d?.protocol == WifiProtocol.DAIKIN && e == WifiError.UNSUPPORTED -> WifiText.adapterUnsupported
    else -> WifiText.error(e, detail)
}

@Composable
fun WifiRemoteScreen(onBack: () -> Unit, vm: WifiRemoteViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.messageId) { if (ui.messageId > 0) ui.message?.let { snackbar.showSnackbar(it) } }
    val d = ui.device

    AppScaffold(d?.name ?: WifiText.title, onBack = onBack, snackbar = snackbar) { padding ->
        when {
            ui.loading -> LoadingBox(Modifier.padding(padding))
            ui.missing || d == null -> StatusPanel(Icons.Filled.ErrorOutline, WifiText.missingTitle, WifiText.missingBody, Modifier.padding(padding),
                actionLabel = WifiText.back, onAction = onBack)
            !d.protocol.controllable -> StatusPanel(kindIcon(d.kind), WifiText.kind(d.kind), WifiText.unsupported, Modifier.padding(padding),
                actionLabel = WifiText.back, onAction = onBack)
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(d, ui.conn, ui.tvStatus, onRetry = vm::connect)
                Spacer(Modifier.height(16.dp))
                val conn = ui.conn
                val pairing = ui.pairing
                when {
                    pairing != null -> AtvPairingPanel(pairing, onSubmit = vm::submitCode, onRestart = vm::startPairing)
                    conn == WifiConn.Pairing -> PromptPairingPanel(d.kind)
                    conn is WifiConn.Offline && conn.error == WifiError.DENIED ->
                        StatusPanel(Icons.Filled.Block, WifiText.deniedTitle, WifiText.deniedBody, actionLabel = WifiText.retry, onAction = vm::connect)
                    d.protocol.isAc -> {
                        val ac = ui.ac
                        when {
                            ac != null -> AcRemote(ac, vm)
                            conn is WifiConn.Offline -> StatusPanel(Icons.Filled.WifiOff, if (conn.reachable) WifiText.problem else WifiText.offline,
                                errorText(d, conn.error, conn.detail),
                                actionLabel = WifiText.retry, onAction = vm::connect)
                            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text(WifiText.readingStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    else -> TvRemote(ui.supported, ui.apps, vm::press, vm::launchApp)
                }
            }
        }
    }
}

@Composable
private fun Header(d: SavedWifiDevice, conn: WifiConn, tvStatus: TvStatus?, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KindBadge(d.kind, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ConnectionChip(conn)
            Spacer(Modifier.height(4.dp))
            if (d.protocol.cloud) Text(WifiText.accountLine(d.manufacturer ?: WifiText.kind(d.kind), d.account), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            else LtrText(d.ip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            tvStatusLine(tvStatus)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        if (conn is WifiConn.Offline && conn.error != WifiError.NOT_PAIRED) {
            TextButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(WifiText.retry)
            }
        }
    }
}

private fun tvStatusLine(s: TvStatus?): String? {
    if (s == null) return null
    val parts = listOfNotNull(
        s.on?.let { if (it) WifiText.tvOn else WifiText.tvStandby },
        s.volume?.let { WifiText.volumeLevel(it, s.volumeMax) },
        if (s.muted == true) WifiText.muted else null,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun ConnectionChip(conn: WifiConn) {
    val s = LocalStatusColors.current
    val (color, text) = when (conn) {
        WifiConn.Connected -> s.success to WifiText.connected
        WifiConn.Connecting, WifiConn.Pairing -> s.warning to WifiText.connecting
        is WifiConn.Offline -> if (conn.reachable) s.warning to WifiText.problem else s.error to WifiText.offline
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/** Samsung / LG: the TV shows an allow prompt. */
@Composable
private fun PromptPairingPanel(kind: WifiDeviceKind) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(96.dp), strokeWidth = 3.dp)
            Icon(kindIcon(kind), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(WifiText.pairingTitle, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(WifiText.pairingBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Android TV: the TV shows a 6-character hex code that the user types here. */
@Composable
private fun AtvPairingPanel(p: AtvPairingUi, onSubmit: (String) -> Unit, onRestart: () -> Unit) {
    if (p is AtvPairingUi.Failed) {
        StatusPanel(Icons.Filled.ErrorOutline, WifiText.pairFailed, p.message, tint = MaterialTheme.colorScheme.error,
            actionLabel = WifiText.startOver, onAction = onRestart)
        return
    }
    var code by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val entering = p is AtvPairingUi.EnterCode || p is AtvPairingUi.Verifying
    LaunchedEffect(entering) { if (entering) runCatching { focus.requestFocus() } }
    val error = (p as? AtvPairingUi.EnterCode)?.error
    LaunchedEffect(error) { if (error != null) code = "" }

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        KindBadge(WifiDeviceKind.TV_ANDROID, size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(WifiText.pairTvTitle, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (p == AtvPairingUi.Starting) {
            CircularProgressIndicator(Modifier.padding(16.dp))
            Text(WifiText.pairTvStarting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            return@Column
        }
        Text(WifiText.pairTvBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        val verifying = p == AtvPairingUi.Verifying
        // A hex code reads left to right in every locale.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = AtvPairingProtocol.normalizeCode(it) },
                label = { Text(WifiText.pairCode) },
                singleLine = true,
                enabled = !verifying,
                isError = error != null,
                supportingText = { if (error != null) Text(error) },
                textStyle = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, letterSpacing = 8.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onSubmit(code) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmit(code) }, enabled = code.length == 6 && !verifying, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (verifying) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp)); Text(WifiText.pairTvVerifying)
            } else Text(WifiText.pair)
        }
        TextButton(onClick = onRestart, enabled = !verifying) { Text(WifiText.startOver) }
    }
}

// ------------------------------------------------------------------ TV

@Composable
private fun Key(key: TvKey, icon: ImageVector?, label: String, supported: Set<TvKey>, press: (TvKey) -> Unit, emphasized: Boolean = false) {
    if (key in supported) RemoteButton(icon, label, { press(key) }, emphasized = emphasized)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvRemote(supported: Set<TvKey>, apps: Set<TvApp>, press: (TvKey) -> Unit, launch: (TvApp) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Key(TvKey.POWER, AppIcons.Power, WifiText.power, supported, press, emphasized = true)
        Key(TvKey.INPUT, AppIcons.Input, WifiText.input, supported, press)
        Key(TvKey.SETTINGS, Icons.Filled.Settings, WifiText.settings, supported, press)
        Key(TvKey.MUTE, AppIcons.Mute, WifiText.mute, supported, press)
    }
    Spacer(Modifier.height(20.dp))
    if (listOf(TvKey.UP, TvKey.DOWN, TvKey.LEFT, TvKey.RIGHT, TvKey.OK).any { it in supported }) {
        Box(Modifier.size(LocalRemoteMetrics.current.padSize).clip(CircleShape), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxSize(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {}
            Box(Modifier.align(Alignment.TopCenter).padding(6.dp)) { Key(TvKey.UP, AppIcons.Up, WifiText.up, supported, press) }
            Box(Modifier.align(Alignment.BottomCenter).padding(6.dp)) { Key(TvKey.DOWN, AppIcons.Down, WifiText.down, supported, press) }
            // Physical directions: never mirrored in RTL.
            Box(Modifier.align(AbsoluteAlignment.CenterLeft).padding(6.dp)) { Key(TvKey.LEFT, AppIcons.Left, WifiText.left, supported, press) }
            Box(Modifier.align(AbsoluteAlignment.CenterRight).padding(6.dp)) { Key(TvKey.RIGHT, AppIcons.Right, WifiText.right, supported, press) }
            Key(TvKey.OK, null, WifiText.ok, supported, press, emphasized = true)
        }
        Spacer(Modifier.height(12.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Key(TvKey.BACK, AppIcons.Back, WifiText.back, supported, press)
        Key(TvKey.HOME, AppIcons.Home, WifiText.home, supported, press)
        Key(TvKey.MENU, AppIcons.Menu, WifiText.menu, supported, press)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Rocker(TvKey.VOL_UP, TvKey.VOL_DOWN, AppIcons.VolumeUp, AppIcons.VolumeDown, WifiText.volume, WifiText.volUp, WifiText.volDown, supported, press)
        Rocker(TvKey.CH_UP, TvKey.CH_DOWN, AppIcons.Up, AppIcons.Down, WifiText.channel, WifiText.chUp, WifiText.chDown, supported, press)
    }
    Spacer(Modifier.height(16.dp))
    // Media transport keeps its universal left-to-right order in every locale.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Key(TvKey.PREVIOUS, AppIcons.Previous, WifiText.previous, supported, press)
            Key(TvKey.REWIND, AppIcons.Rewind, WifiText.rewind, supported, press)
            if (TvKey.PLAY_PAUSE in supported) Key(TvKey.PLAY_PAUSE, AppIcons.Play, WifiText.playPause, supported, press)
            else {
                Key(TvKey.PLAY, AppIcons.Play, WifiText.play, supported, press)
                Key(TvKey.PAUSE, AppIcons.Pause, WifiText.pause, supported, press)
            }
            Key(TvKey.FAST_FORWARD, AppIcons.FastForward, WifiText.forward, supported, press)
            Key(TvKey.NEXT, AppIcons.Next, WifiText.next, supported, press)
        }
    }
    if (listOf(TvKey.INFO, TvKey.CAPTIONS, TvKey.GUIDE).any { it in supported }) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Key(TvKey.INFO, Icons.Filled.Info, WifiText.info, supported, press)
            Key(TvKey.CAPTIONS, Icons.Filled.ClosedCaption, WifiText.subtitles, supported, press)
            Key(TvKey.GUIDE, Icons.Filled.DateRange, WifiText.guide, supported, press)
        }
    }
    if (listOf(TvKey.RED, TvKey.GREEN, TvKey.YELLOW, TvKey.BLUE).all { it in supported }) {
        Spacer(Modifier.height(16.dp))
        // Colour keys are laid out red→blue like on every physical remote, also in RTL.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ColorKey(Color(0xFFE53935), WifiText.red) { press(TvKey.RED) }
                ColorKey(Color(0xFF43A047), WifiText.green) { press(TvKey.GREEN) }
                ColorKey(Color(0xFFFDD835), WifiText.yellow) { press(TvKey.YELLOW) }
                ColorKey(Color(0xFF1E88E5), WifiText.blue) { press(TvKey.BLUE) }
            }
        }
    }
    if (TvKey.digits.all { it in supported }) {
        Spacer(Modifier.height(12.dp))
        var showDigits by rememberSaveable { mutableStateOf(false) }
        FilterChip(selected = showDigits, onClick = { showDigits = !showDigits }, label = { Text(WifiText.numbers) })
        if (showDigits) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(0)).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            row.forEach { n -> RemoteButton(null, n.toString(), { press(TvKey.digits[n]) }) }
                        }
                    }
                }
            }
        }
    }
    if (apps.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(WifiText.apps, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            apps.forEach { app -> FilledTonalButton(onClick = { launch(app) }) { Text(appName(app)) } }
        }
    }
    Spacer(Modifier.height(24.dp))
}

/** Brand names stay as-is in every language. */
private fun appName(app: TvApp) = when (app) {
    TvApp.YOUTUBE -> "YouTube"
    TvApp.NETFLIX -> "Netflix"
    TvApp.PRIME_VIDEO -> "Prime Video"
    TvApp.DISNEY_PLUS -> "Disney+"
}

@Composable
private fun ColorKey(color: Color, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = color,
        modifier = Modifier.width(64.dp).height(28.dp).semantics { contentDescription = label }
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(50))) {}
}

@Composable
private fun Rocker(up: TvKey, down: TvKey, upIcon: ImageVector, downIcon: ImageVector, title: String, upLabel: String, downLabel: String, supported: Set<TvKey>, press: (TvKey) -> Unit) {
    if (up !in supported && down !in supported) return
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Key(up, upIcon, upLabel, supported, press)
            Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(6.dp))
            Key(down, downIcon, downLabel, supported, press)
        }
    }
}

// ------------------------------------------------------------------ AC

internal fun acModeIcon(m: WifiAcMode): ImageVector = when (m) {
    WifiAcMode.AUTO -> AppIcons.Auto
    WifiAcMode.COOL -> AppIcons.Snowflake
    WifiAcMode.HEAT -> AppIcons.Sun
    WifiAcMode.DRY -> AppIcons.Drop
    WifiAcMode.FAN -> AppIcons.Wind
}

private val modeOrder = listOf(WifiAcMode.AUTO, WifiAcMode.COOL, WifiAcMode.HEAT, WifiAcMode.DRY, WifiAcMode.FAN)
private val fanOrder = listOf(WifiFan.AUTO, WifiFan.LOW, WifiFan.MEDIUM, WifiFan.HIGH)

/** Same look as the IR AC remote (feature/acremote): arc dial, −/power/+, mode and fan segments. Values are the unit's real state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcRemote(st: WifiAcStatus, vm: WifiRemoteViewModel) {
    val s = st.state
    val caps = st.caps
    val status = LocalStatusColors.current
    val modeColor by animateColorAsState(
        when {
            !s.power -> MaterialTheme.colorScheme.outline
            s.mode == WifiAcMode.HEAT -> status.heat
            s.mode == WifiAcMode.COOL -> status.cool
            else -> MaterialTheme.colorScheme.primary
        }, label = "wifiAcColor",
    )
    val fahrenheit = LocalAppSettings.current.temperatureUnit == TemperatureUnit.FAHRENHEIT
    fun shownTemp(c: Double) = if (fahrenheit) Math.round(c * 9 / 5 + 32).toInt() else Math.round(c).toInt()
    val unit = if (fahrenheit) "°F" else "°C"
    val ignored = s.mode in caps.tempIgnoredIn
    val range = caps.tempRange
    val canDown = !ignored && s.targetC > range.first
    val canUp = !ignored && s.targetC < range.last
    val fraction by animateFloatAsState(
        if (ignored || range.last <= range.first) 0f else (s.targetC - range.first).toFloat() / (range.last - range.first), label = "wifiDial",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(Modifier.size(236.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = stroke)
            if (s.power) drawArc(Brush.sweepGradient(listOf(modeColor.copy(alpha = 0.55f), modeColor, modeColor.copy(alpha = 0.55f))),
                startAngle = 135f, sweepAngle = 270f * fraction.coerceIn(0.02f, 1f), useCenter = false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(acModeIcon(s.mode), null, tint = modeColor, modifier = Modifier.size(22.dp))
            val shown = shownTemp(s.targetC.toDouble())
            AnimatedContent(targetState = shown, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "wifiTemp") { t ->
                // Number and unit are one token; keep "24°C" in that order in RTL locales too.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics { contentDescription = "$t $unit" }) {
                        Text(if (ignored) WifiText.noSetPoint else t.toString(), style = MaterialTheme.typography.displayLarge, color = modeColor)
                        Text(unit, style = MaterialTheme.typography.titleLarge, color = modeColor, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            Text(if (s.power) WifiText.acOn else WifiText.acOff, style = MaterialTheme.typography.titleMedium, color = modeColor)
            st.roomC?.let { room ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text("${WifiText.room} ${shownTemp(room)}$unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RemoteButton(AppIcons.Minus, WifiText.tempDown, onClick = { if (canDown) vm.changeTemp(-1) })
        RemoteButton(AppIcons.Power, WifiText.power, onClick = { vm.togglePower() }, size = 84.dp, emphasized = s.power)
        RemoteButton(AppIcons.Plus, WifiText.tempUp, onClick = { if (canUp) vm.changeTemp(+1) })
    }
    Spacer(Modifier.height(20.dp))

    val modes = modeOrder.filter { it in caps.modes }
    if (modes.isNotEmpty()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(selected = s.mode == m, onClick = { vm.setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    icon = { Icon(acModeIcon(m), null, Modifier.size(18.dp)) }) { Text(WifiText.modeName(m), maxLines = 1) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    val fans = fanOrder.filter { it in caps.fans }
    if (fans.isNotEmpty()) {
        Text(WifiText.fan, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            fans.forEachIndexed { i, f ->
                SegmentedButton(selected = s.fan == f, onClick = { vm.setFan(f) }, shape = SegmentedButtonDefaults.itemShape(i, fans.size)) {
                    Text(WifiText.fanName(f), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (caps.swing) {
        Row(Modifier.fillMaxWidth()) {
            FilterChip(selected = s.swing, onClick = { vm.toggleSwing() }, label = { Text(WifiText.swing) },
                leadingIcon = { Icon(AppIcons.Swing, null, Modifier.size(18.dp)) })
        }
    }
    Spacer(Modifier.height(24.dp))
}
