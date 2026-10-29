package com.universalir.feature.devices

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.AppIcons
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.DeviceAvatar
import com.universalir.core.ui.LocalAppSettings
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.categoryName
import com.universalir.core.ui.lookOf
import com.universalir.data.local.RoomEntity
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.SettingsRepository
import com.universalir.domain.model.categoryOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SavedDevicesViewModel @Inject constructor(
    repo: DeviceRepository,
    private val settings: SettingsRepository,
) : DeviceActionsViewModel(repo) {
    val devices = repo.listDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val rooms = repo.rooms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setGrid(grid: Boolean) = viewModelScope.launch { settings.setSavedDevicesAsGrid(grid) }
}

@Composable
fun SavedDevicesScreen(onOpen: (Long) -> Unit, onAdd: () -> Unit, onBack: () -> Unit, vm: SavedDevicesViewModel = hiltViewModel()) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val grid = LocalAppSettings.current.savedDevicesAsGrid
    var selected by remember { mutableStateOf<SavedDeviceEntity?>(null) }
    var arranging by remember { mutableStateOf(false) }
    var pendingOrder by remember { mutableStateOf<List<Long>?>(null) }

    AppScaffold(
        stringResource(if (arranging) R.string.home_arrange else R.string.nav_devices),
        onBack = if (arranging) ({ arranging = false; pendingOrder = null }) else onBack,
        actions = {
            if (arranging) {
                TextButton(onClick = { pendingOrder?.let(vm::reorder); pendingOrder = null; arranging = false }) {
                    Icon(Icons.Filled.Check, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_done))
                }
            } else {
                IconButton(onClick = { arranging = true }, enabled = (devices?.size ?: 0) > 1) { Icon(AppIcons.Arrange, stringResource(R.string.home_arrange)) }
                IconButton(onClick = { vm.setGrid(!grid) }) {
                    Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, stringResource(if (grid) R.string.devices_list_view else R.string.devices_grid_view))
                }
            }
        },
        floatingActionButton = {
            if (!arranging) ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.home_add_device)) })
        },
    ) { padding ->
        val list = devices ?: return@AppScaffold
        if (list.isEmpty()) {
            StatusPanel(AppIcons.Remote, stringResource(R.string.empty_devices_title), stringResource(R.string.empty_devices_body),
                Modifier.padding(padding), actionLabel = stringResource(R.string.home_add_device), onAction = onAdd)
            return@AppScaffold
        }
        if (arranging) {
            Column(Modifier.padding(padding)) {
                Text(stringResource(R.string.home_arrange_hint), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArrangeGrid(list, rooms, onOrderChanged = { pendingOrder = it })
            }
            return@AppScaffold
        }
        LazyVerticalGrid(
            if (grid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
            Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(if (grid) 12.dp else 8.dp),
        ) {
            items(list, key = { it.id }, span = { d -> GridItemSpan(if (grid) d.settings().tileSize.span(maxLineSpan) else 1) }) { d ->
                if (grid) DeviceTile(d, rooms, onClick = { onOpen(d.id) }, onLongClick = { selected = d }, modifier = Modifier.animateItem())
                else DeviceRow(d, rooms, onClick = { onOpen(d.id) }, onLongClick = { selected = d }, modifier = Modifier.animateItem())
            }
        }
    }
    selected?.let { d ->
        // Re-read the live row so the sheet reflects changes made while it is open.
        val live = devices?.firstOrNull { it.id == d.id } ?: return@let
        DeviceActionsHost(live, rooms, vm, onOpen = onOpen, onArrange = { arranging = true }, onDismiss = { selected = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceRow(d: SavedDeviceEntity, rooms: List<RoomEntity>, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val settings = remember(d.customSettings) { d.settings() }
    val cat = categoryOf(d.category)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(lookOf(cat, settings), size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val room = rooms.firstOrNull { it.id == d.roomId }?.name
                Text(listOfNotNull(stringResource(categoryName(cat)), d.brandName, room).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (!settings.showOnHome) Icon(Icons.Filled.VisibilityOff, stringResource(R.string.device_hidden_home), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (d.favorite) Icon(Icons.Filled.Star, null, tint = LocalStatusColors.current.warning)
        }
    }
}
