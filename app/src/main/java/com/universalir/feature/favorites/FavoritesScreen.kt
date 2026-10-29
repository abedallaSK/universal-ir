package com.universalir.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.SectionHeader
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.categoryIcon
import com.universalir.core.ui.describeOutcome
import com.universalir.data.local.FavoriteCommandEntity
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.LoadResult
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.categoryOf
import com.universalir.domain.usecase.RemoteController
import com.universalir.domain.usecase.SendOutcome
import com.universalir.feature.acremote.modeName
import com.universalir.ir.protocol.ac.AcState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val devices: DeviceRepository,
    private val codes: CodeRepository,
    private val remote: RemoteController,
) : ViewModel() {
    val allDevices = devices.listDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val commands = devices.favoriteCommands().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val outcome = MutableStateFlow<SendOutcome?>(null)

    /** One-tap action: sends the saved command or AC state through the normal pipeline. */
    fun run(f: FavoriteCommandEntity) = viewModelScope.launch {
        val d = devices.device(f.deviceId) ?: return@launch
        val set = (codes.load(d.codeSetId) as? LoadResult.Ok)?.set ?: return@launch run { outcome.value = SendOutcome.EncodeError(listOf("Code set unavailable")) }
        val ds = DeviceSettings.decode(d.customSettings) ?: DeviceSettings()
        outcome.value = if (f.acStateJson != null) {
            val state = runCatching { DeviceSettings.json.decodeFromString(AcState.serializer(), f.acStateJson) }.getOrNull()
                ?: return@launch run { outcome.value = SendOutcome.EncodeError(listOf("Corrupt favourite")) }
            val result = remote.sendAc(set, state, ds.lastSentAcState)
            if (result is SendOutcome.Success) devices.saveSettings(d.id, ds.copy(acState = state, lastSentAcState = state))
            result
        } else {
            remote.sendCommand(set, f.commandName ?: "")
        }
        devices.markUsed(d.id)
    }

    fun delete(f: FavoriteCommandEntity) = viewModelScope.launch { devices.deleteFavoriteCommand(f.id) }
}

@Composable
fun FavoritesScreen(onOpenDevice: (Long) -> Unit, onBack: () -> Unit, vm: FavoritesViewModel = hiltViewModel()) {
    val devices by vm.allDevices.collectAsStateWithLifecycle()
    val commands by vm.commands.collectAsStateWithLifecycle()
    val outcome by vm.outcome.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(outcome) { outcome?.let { snackbar.showSnackbar(describeOutcome(context, it)) } }

    AppScaffold(stringResource(R.string.nav_favorites), onBack = onBack, snackbar = snackbar) { padding ->
        val favDevices = devices.filter { it.favorite }
        if (favDevices.isEmpty() && commands.isEmpty()) {
            StatusPanel(Icons.Filled.FavoriteBorder, stringResource(R.string.empty_fav_title), stringResource(R.string.empty_fav_body), Modifier.padding(padding))
            return@AppScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (favDevices.isNotEmpty()) item { SectionHeader(stringResource(R.string.fav_devices)) }
            items(favDevices, key = { "d${it.id}" }) { d ->
                Surface(onClick = { onOpenDevice(d.id) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(categoryIcon(categoryOf(d.category)), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(d.displayName, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (commands.isNotEmpty()) item { SectionHeader(stringResource(R.string.fav_commands)) }
            items(commands, key = { "c${it.id}" }) { f ->
                val device = devices.firstOrNull { it.id == f.deviceId }
                val acState = f.acStateJson?.let { runCatching { DeviceSettings.json.decodeFromString(AcState.serializer(), it) }.getOrNull() }
                Surface(onClick = { vm.run(f) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (acState != null) Icons.Filled.AcUnit else Icons.Filled.TouchApp, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.label, style = MaterialTheme.typography.titleSmall)
                            val detail = if (acState != null) {
                                if (acState.power) "${stringResource(modeName(acState.mode))} ${acState.temperatureCelsius}°" else stringResource(R.string.ac_power_off)
                            } else f.commandName ?: ""
                            Text(listOfNotNull(device?.displayName, detail).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.delete(f) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
    }
}
