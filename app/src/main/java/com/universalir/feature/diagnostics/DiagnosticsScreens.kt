package com.universalir.feature.diagnostics

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.ir.HardwareStatus
import com.universalir.core.ir.IrTransmitter
import com.universalir.core.ir.TransmitResult
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.AppCard
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.KeyValueRow
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.SectionHeader
import com.universalir.core.ui.VerificationBadge
import com.universalir.core.ui.describeHardware
import com.universalir.core.ui.describeTransmit
import com.universalir.core.ui.formatRanges
import com.universalir.data.local.DiagnosticRecordEntity
import com.universalir.data.repository.DeviceRepository
import com.universalir.domain.usecase.RemoteController
import com.universalir.feature.acremote.fanName
import com.universalir.feature.acremote.modeName
import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.IrTransmission
import com.universalir.ir.core.PatternValidator
import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.ProtocolRegistry
import com.universalir.ir.protocol.ac.AcProtocol
import com.universalir.ir.protocol.ac.AcState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

// ------------------------------------------------------------------ Page 3: Hardware diagnostics

@HiltViewModel
class HardwareViewModel @Inject constructor(
    private val transmitter: IrTransmitter,
    private val devices: DeviceRepository,
) : ViewModel() {
    val status = MutableStateFlow<HardwareStatus?>(null)
    val burst = MutableStateFlow<TransmitResult?>(null)
    val history = devices.diagnostics().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun run() = viewModelScope.launch {
        val s = transmitter.checkHardwareSupport()
        status.value = s
        devices.addDiagnostic(
            DiagnosticRecordEntity(
                timestamp = System.currentTimeMillis(), phone = phone(), status = s.javaClass.simpleName,
                carrierRanges = (s as? HardwareStatus.Available)?.carrierRanges?.let(::formatRanges) ?: "",
                detail = (s as? HardwareStatus.Error)?.message,
            ),
        )
    }

    /** A 10 ms unmodulated-data carrier burst: decodes as no protocol, so it cannot operate a device. */
    fun testBurst() = viewModelScope.launch {
        burst.value = transmitter.transmit(IrTransmission.single(IrPattern(38_000, intArrayOf(10_000)), "burst"))
    }

    fun clear() = viewModelScope.launch { devices.clearDiagnostics() }

    fun phone() = "${Build.MANUFACTURER} ${Build.MODEL}"
}

@Composable
fun HardwareDiagnosticsScreen(onBack: () -> Unit, vm: HardwareViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val burst by vm.burst.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.run() }
    val c = LocalStatusColors.current

    AppScaffold(stringResource(R.string.nav_hardware), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    val ok = status is HardwareStatus.Available
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, tint = if (ok) c.success else c.warning, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(if (ok) R.string.hw_emitter_yes else R.string.hw_emitter_no), style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(status?.let { describeHardware(context, it) } ?: "…", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow(stringResource(R.string.hw_service), stringResource(
                        when (status) {
                            is HardwareStatus.Available, HardwareStatus.NoEmitter -> R.string.hw_service_ok
                            HardwareStatus.NoFeature -> R.string.hw_service_not_declared
                            else -> R.string.hw_service_missing
                        },
                    ))
                    KeyValueRow(stringResource(R.string.hw_carriers), (status as? HardwareStatus.Available)?.carrierRanges?.takeIf { it.isNotEmpty() }?.let(::formatRanges)
                        ?: stringResource(R.string.hw_not_reported), ltrValue = true)
                    KeyValueRow(stringResource(R.string.hw_phone), vm.phone(), ltrValue = true)
                    KeyValueRow(stringResource(R.string.hw_android), "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", ltrValue = true)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.run() }) { Text(stringResource(R.string.hw_run_again)) }
                        OutlinedButton(onClick = { vm.testBurst() }, enabled = status is HardwareStatus.Available) { Text(stringResource(R.string.hw_test_burst)) }
                    }
                    burst?.let { Text(describeTransmit(context, it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    Text(stringResource(R.string.hw_burst_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.hw_limits_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.hw_limits_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionHeader(stringResource(R.string.hw_history)) { TextButton(onClick = { vm.clear() }) { Text(stringResource(R.string.action_clear)) } } }
            items(history, key = { it.id }) { r ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.timestamp)) + " · " + r.status, style = MaterialTheme.typography.bodyMedium)
                    LtrText(listOf(r.phone, r.carrierRanges, r.detail ?: "").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Page 19: Protocol diagnostics

@HiltViewModel
class ProtocolDiagnosticsViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val remote: RemoteController,
    private val transmitter: IrTransmitter,
) : ViewModel() {
    val selected = MutableStateFlow(handle.get<String>("protocolId")?.takeIf { ProtocolRegistry.info(it) != null } ?: ProtocolRegistry.acProtocols.first().info.id)
    val testState = MutableStateFlow(AcState(power = true))
    val lastEvent = remote.lastEvent
    val preview = MutableStateFlow<EncodeResult?>(null)

    fun encode() {
        val p = ProtocolRegistry.ac(selected.value) ?: return
        preview.value = p.encodeCompleteState(testState.value, previous = AcState(power = true))
    }

    fun transmit() = viewModelScope.launch {
        val p = ProtocolRegistry.ac(selected.value) ?: return@launch
        remote.send(p.info.displayName, "diagnostics", p.encodeCompleteState(testState.value, AcState(power = true)))
    }

    fun ranges() = transmitter.getSupportedCarrierRanges()
}

@Composable
fun ProtocolDiagnosticsScreen(onBack: () -> Unit, vm: ProtocolDiagnosticsViewModel = hiltViewModel()) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val state by vm.testState.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val last by vm.lastEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val info = ProtocolRegistry.info(selected)
    val ac: AcProtocol? = ProtocolRegistry.ac(selected)
    LaunchedEffect(selected, state) { vm.encode() }

    AppScaffold(stringResource(R.string.nav_protocols), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ProtocolRegistry.all.map { it.info.id }) { id -> FilterChip(id == selected, { vm.selected.value = id; vm.preview.value = null }, label = { LtrText(id) }) }
                }
            }
            if (info != null) item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(info.displayName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp)); VerificationBadge(info.verification)
                    KeyValueRow(stringResource(R.string.pd_id), info.id, ltrValue = true)
                    KeyValueRow(stringResource(R.string.pd_carrier), "${info.carrierHz} Hz", ltrValue = true)
                    KeyValueRow(stringResource(R.string.pd_complete_state), stringResource(if (info.completeState) R.string.yes else R.string.no))
                    Text(stringResource(R.string.pd_frame), style = MaterialTheme.typography.labelLarge)
                    LtrText(info.frameDescription, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.pd_evidence), style = MaterialTheme.typography.labelLarge)
                    info.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (info.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.pd_warnings), style = MaterialTheme.typography.labelLarge, color = LocalStatusColors.current.warning)
                        info.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.pd_sources), style = MaterialTheme.typography.labelLarge)
                    info.sources.forEach { s ->
                        TextButton(onClick = { uri.openUri(s.url) }) { LtrText("${s.name} (${s.license})", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (ac != null) item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pd_test_state), style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(state.power, { vm.testState.value = state.copy(power = !state.power) }, label = { Text(stringResource(R.string.ac_power)) }) }
                        items(ac.capabilities.modes.toList()) { m -> FilterChip(state.mode == m, { vm.testState.value = state.copy(mode = m) }, label = { Text(stringResource(modeName(m))) }) }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ac.capabilities.fanSpeedsFor(state.mode).toList()) { f -> FilterChip(state.fanSpeed == f, { vm.testState.value = state.copy(fanSpeed = f) }, label = { Text(stringResource(fanName(f))) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.testState.value = state.copy(temperatureCelsius = state.temperatureCelsius - 1) }) { Text("−") }
                        Text("${state.temperatureCelsius} °C")
                        TextButton(onClick = { vm.testState.value = state.copy(temperatureCelsius = state.temperatureCelsius + 1) }) { Text("+") }
                    }
                    when (val p = preview) {
                        is EncodeResult.Success -> {
                            KeyValueRow(stringResource(R.string.pd_summary), p.summary)
                            KeyValueRow(stringResource(R.string.pd_messages), p.transmission.messages.size.toString())
                            p.transmission.messages.forEachIndexed { i, m ->
                                val issues = PatternValidator.validate(m.pattern, vm.ranges().ifEmpty { null })
                                LtrText("#${i + 1} ${m.label}: ${m.pattern.size} entries, ${m.pattern.frameStarts.size} frame(s), ${m.pattern.totalDurationUs / 1000.0} ms" +
                                    (if (m.pauseAfterMs > 0) ", pause ${m.pauseAfterMs} ms" else ""), style = MaterialTheme.typography.bodySmall)
                                issues.forEach { Text("⚠ ${it.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                            Text(stringResource(R.string.pd_payload), style = MaterialTheme.typography.labelLarge)
                            p.payloads.forEach { LtrText(it, style = MaterialTheme.typography.bodySmall) }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.transmit() }) { Text(stringResource(R.string.pd_transmit)) }
                        }
                        is EncodeResult.Failure -> p.issues.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        null -> {}
                    }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pd_last), style = MaterialTheme.typography.titleSmall)
                    val e = last
                    if (e == null) Text(stringResource(R.string.pd_last_none), style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(DateFormat.getTimeInstance().format(Date(e.timestamp)) + " · " + e.codeSetTitle, style = MaterialTheme.typography.bodySmall)
                        LtrText(e.command, style = MaterialTheme.typography.bodySmall)
                        when (val enc = e.encode) {
                            is EncodeResult.Failure -> enc.issues.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            is EncodeResult.Success -> enc.payloads.forEach { LtrText(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        e.transmit?.let { Text(describeTransmit(context, it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
