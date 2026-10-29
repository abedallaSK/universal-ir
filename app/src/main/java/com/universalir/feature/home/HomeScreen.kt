package com.universalir.feature.home

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.universalir.R
import com.universalir.app.navigation.Routes
import com.universalir.core.ir.HardwareStatus
import com.universalir.core.ir.IrTransmitter
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.AppIcons
import com.universalir.core.ui.DeviceAvatar
import com.universalir.core.ui.LocalAppSettings
import com.universalir.core.ui.LtrText
import com.universalir.core.ui.StatusPanel
import com.universalir.core.ui.categoryIcon
import com.universalir.core.ui.look
import com.universalir.data.local.ModelSearchRow
import com.universalir.data.local.RoomEntity
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.repository.AppSettings
import com.universalir.data.repository.CodeRepository
import com.universalir.data.repository.DeviceRepository
import com.universalir.data.repository.HomeLayout
import com.universalir.data.repository.SettingsRepository
import com.universalir.domain.model.TextNormalizer
import com.universalir.domain.model.TileSize
import com.universalir.domain.model.categoryOf
import com.universalir.feature.devices.ArrangeGrid
import com.universalir.feature.devices.DeviceActionsHost
import com.universalir.feature.devices.DeviceActionsViewModel
import com.universalir.feature.devices.DeviceRow
import com.universalir.feature.devices.DeviceTile
import com.universalir.feature.devices.settings
import com.universalir.feature.devices.span
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    repo: DeviceRepository,
    codes: CodeRepository,
    private val transmitter: IrTransmitter,
    private val settings: SettingsRepository,
    private val wifiStore: com.universalir.data.wifi.WifiDeviceStore,
) : DeviceActionsViewModel(repo) {
    val wifi = wifiStore.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun renameWifi(id: String, name: String) = viewModelScope.launch { wifiStore.rename(id, name) }
    fun removeWifi(id: String) = viewModelScope.launch { wifiStore.remove(id) }
    val devices: StateFlow<List<SavedDeviceEntity>?> = repo.listDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val recent = repo.recentDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rooms = repo.rooms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val query = MutableStateFlow("")
    val catalogHits: StateFlow<List<ModelSearchRow>> = query.flatMapLatest { q ->
        if (TextNormalizer.normalize(q).length < 2) flowOf(emptyList()) else codes.searchDevices(q, limit = 30)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hardware = MutableStateFlow<HardwareStatus?>(null)

    fun refreshHardware() { hardware.value = transmitter.checkHardwareSupport() }
    fun setTitle(v: String) = viewModelScope.launch { settings.setHomeTitle(v) }
    fun setLayout(v: HomeLayout) = viewModelScope.launch { settings.setHomeLayout(v) }
    fun setSections(s: AppSettings) = viewModelScope.launch { settings.setHomeSections(s.homeShowQuick, s.homeShowFavorites, s.homeShowRecent, s.homeShowRooms) }

    /** Reorders only the devices shown on home; hidden ones keep their relative order after them. */
    fun reorderShown(shownIds: List<Long>, all: List<SavedDeviceEntity>) = reorder(shownIds + all.map { it.id }.filter { it !in shownIds })
}

private data class QuickAction(val label: Int, val icon: ImageVector, val colors: Pair<Color, Color>, val route: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = hiltViewModel()) {
    val settings = LocalAppSettings.current
    val devices by vm.devices.collectAsStateWithLifecycle()
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val hits by vm.catalogHits.collectAsStateWithLifecycle()
    val hardware by vm.hardware.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var customizing by rememberSaveable { mutableStateOf(false) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    var pendingOrder by remember { mutableStateOf<List<Long>?>(null) }
    var selected by remember { mutableStateOf<SavedDeviceEntity?>(null) }
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshHardware() }
    val open: (Long) -> Unit = { nav.navigate(Routes.remote(it)) }
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    // Until the check has run, assume an emitter so the screen doesn't flash into Wi-Fi-only mode.
    val irOn = settings.irEnabled(hasEmitter = hardware == null || hardware is HardwareStatus.Available)
    var wifiSelected by remember { mutableStateOf<com.universalir.data.wifi.SavedWifiDevice?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (!arranging) ExtendedFloatingActionButton(onClick = { if (irOn) adding = true else nav.navigate(Routes.WIFI) },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.action_add)) })
        },
    ) { padding ->
        val all = devices
        val shownAll = all.orEmpty().filter { it.settings().showOnHome }
        if (arranging && all != null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_arrange), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.home_arrange_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { pendingOrder?.let { vm.reorderShown(it, all) }; pendingOrder = null; arranging = false }) {
                        Icon(Icons.Filled.Check, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_done))
                    }
                }
                Spacer(Modifier.height(12.dp))
                ArrangeGrid(shownAll, rooms, onOrderChanged = { pendingOrder = it })
            }
            return@Scaffold
        }
        val list = settings.homeLayout == HomeLayout.LIST
        val titleYours = stringResource(R.string.home_your_devices)
        val titleWifi = stringResource(R.string.nav_wifi)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(greeting()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(settings.homeTitle.ifBlank { stringResource(R.string.home_title_default) }, style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, stringResource(R.string.action_search))
                    }
                    IconButton(onClick = { customizing = true }) { Icon(Icons.Filled.Tune, stringResource(R.string.home_customize)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            listOf(
                                Triple(R.string.nav_devices, Icons.Filled.ViewList, Routes.DEVICES),
                                Triple(R.string.nav_rooms, Icons.Filled.MeetingRoom, Routes.ROOMS),
                                Triple(R.string.fav_commands, Icons.Filled.Favorite, Routes.FAVORITES),
                                Triple(R.string.nav_wifi, Icons.Filled.Wifi, Routes.WIFI),
                                Triple(R.string.nav_settings, Icons.Filled.Settings, Routes.SETTINGS),
                                Triple(R.string.nav_about, Icons.Filled.Info, Routes.ABOUT),
                            ).forEach { (label, icon, route) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) }, onClick = { menu = false; nav.navigate(route) })
                            }
                        }
                    }
                }
            }
            if (searching) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = query, onValueChange = { vm.query.value = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        placeholder = { Text(stringResource(R.string.home_search_hint)) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    )
                }
            }
            if (irOn && hardware != null && hardware !is HardwareStatus.Available) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(onClick = { nav.navigate(Routes.HARDWARE) }, shape = MaterialTheme.shapes.medium, color = LocalStatusColors.current.warning.copy(alpha = 0.12f)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = LocalStatusColors.current.warning)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.home_no_ir_banner), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            val q = TextNormalizer.normalize(query)
            if (searching && q.isNotEmpty()) {
                val local = all.orEmpty().filter { TextNormalizer.normalize(it.displayName + it.brandName).contains(q) }
                if (local.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { HomeSection(stringResource(R.string.home_your_devices)) }
                    items(local, key = { "s${it.id}" }) { d -> DeviceTile(d, rooms, { open(d.id) }, { selected = d }, size = TileSize.SMALL) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { HomeSection(stringResource(R.string.home_catalog_results)) }
                if (hits.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.home_no_results), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(hits, key = { "c${it.modelId}" }, span = { GridItemSpan(maxLineSpan) }) { hit ->
                    Surface(onClick = { nav.navigate(Routes.models(hit.deviceType, hit.brandId)) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(categoryIcon(categoryOf(hit.deviceType)), null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(hit.brandName, style = MaterialTheme.typography.titleSmall)
                                LtrText(hit.modelNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
                return@LazyVerticalGrid
            }

            if (all == null) return@LazyVerticalGrid
            val wifiOpen: (String) -> Unit = { nav.navigate(Routes.wifiRemote(it)) }
            if (!irOn) {
                // Wi-Fi-only phones: just the Wi-Fi devices, no infrared screens.
                if (wifi.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    StatusPanel(AppIcons.Wifi, stringResource(R.string.home_wifi_empty_title), stringResource(R.string.home_wifi_empty_body),
                        actionLabel = stringResource(R.string.wifi_find), onAction = { nav.navigate(Routes.WIFI) })
                } else wifiSection(wifi, title = titleYours, onOpen = wifiOpen, onLong = { wifiSelected = it })
                return@LazyVerticalGrid
            }
            if (all.isEmpty() && wifi.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    StatusPanel(
                        icon = AppIcons.Remote,
                        title = stringResource(R.string.empty_devices_title),
                        message = stringResource(R.string.empty_devices_body),
                        actionLabel = stringResource(R.string.home_add_device),
                        onAction = { nav.navigate(Routes.ADD) },
                        secondaryLabel = stringResource(R.string.nav_wifi),
                        onSecondary = { nav.navigate(Routes.WIFI) },
                    )
                }
                return@LazyVerticalGrid
            }

            // One row of filters gives access to everything: favourites, Wi-Fi, each room, room management.
            item(span = { GridItemSpan(maxLineSpan) }) {
                HomeFilters(filter, hasFavorites = all.any { it.favorite }, hasWifi = wifi.isNotEmpty() && all.isNotEmpty(),
                    rooms = if (settings.homeShowRooms) rooms else emptyList(), onManageRooms = { nav.navigate(Routes.ROOMS) }) { filter = it }
            }
            val shown = when {
                filter == "fav" -> shownAll.filter { it.favorite }
                filter == "wifi" -> emptyList()
                filter.startsWith("room:") -> shownAll.filter { it.roomId == filter.removePrefix("room:").toLongOrNull() }
                else -> shownAll
            }
            if (shown.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                HomeSection(stringResource(R.string.home_your_devices), count = shown.size) {
                    if (filter == "all" && shownAll.size > 1) TextButton(onClick = { arranging = true }) {
                        Icon(AppIcons.Arrange, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.home_arrange_short))
                    }
                }
            } else if (filter != "wifi" && filter != "all") item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.home_filter_empty), Modifier.fillMaxWidth().padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown, key = { it.id }, span = { d -> GridItemSpan(if (list) maxLineSpan else d.settings().tileSize.span(maxLineSpan)) }) { d ->
                if (list) DeviceRow(d, rooms, onClick = { open(d.id) }, onLongClick = { selected = d }, modifier = Modifier.animateItem())
                else DeviceTile(d, rooms, onClick = { open(d.id) }, onLongClick = { selected = d }, modifier = Modifier.animateItem())
            }
            val hidden = all.size - shownAll.size
            if (filter == "all" && hidden > 0) item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { nav.navigate(Routes.DEVICES) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_hidden_count, hidden))
                }
            }
            if (wifi.isNotEmpty() && (filter == "all" || filter == "wifi")) wifiSection(wifi, title = titleWifi, onOpen = wifiOpen, onLong = { wifiSelected = it })
        }
    }
    selected?.let { d ->
        val live = devices?.firstOrNull { it.id == d.id } ?: return@let
        DeviceActionsHost(live, rooms, vm, onOpen = open, onArrange = { arranging = true }, onDismiss = { selected = null })
    }
    wifiSelected?.let { d -> WifiDeviceSheet(d, onOpen = { wifiSelected = null; nav.navigate(Routes.wifiRemote(d.id)) },
        onRename = { vm.renameWifi(d.id, it) }, onRemove = { vm.removeWifi(d.id) }, onDismiss = { wifiSelected = null }) }
    if (adding) AddSheet(onPick = { adding = false; nav.navigate(it) }, onDismiss = { adding = false })
    if (customizing) CustomizeHomeSheet(settings, devices.orEmpty(), vm, onArrange = { customizing = false; arranging = true }) { customizing = false }
}

private fun greeting(): Int = when (LocalTime.now().hour) {
    in 5..11 -> R.string.home_greet_morning
    in 12..17 -> R.string.home_greet_afternoon
    else -> R.string.home_greet_evening
}

@Composable
private fun HomeSection(title: String, count: Int? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Home filter chips: all, favourites, Wi-Fi, each room, then a shortcut to manage rooms. */
@Composable
private fun HomeFilters(selected: String, hasFavorites: Boolean, hasWifi: Boolean, rooms: List<RoomEntity>, onManageRooms: () -> Unit, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        item { FilterChip(selected == "all", { onSelect("all") }, label = { Text(stringResource(R.string.filter_all)) }) }
        if (hasFavorites) item {
            FilterChip(selected == "fav", { onSelect("fav") }, label = { Text(stringResource(R.string.home_favorites)) },
                leadingIcon = { Icon(AppIcons.Star, null, Modifier.size(18.dp), tint = LocalStatusColors.current.warning) })
        }
        if (hasWifi) item {
            FilterChip(selected == "wifi", { onSelect("wifi") }, label = { Text(stringResource(R.string.nav_wifi_short)) },
                leadingIcon = { Icon(AppIcons.Wifi, null, Modifier.size(18.dp)) })
        }
        items(rooms, key = { it.id }) { r ->
            FilterChip(selected == "room:${r.id}", { onSelect("room:${r.id}") }, label = { Text(r.name) },
                leadingIcon = { Icon(com.universalir.feature.rooms.roomIcon(r.icon), null, Modifier.size(18.dp), tint = Color(r.color)) })
        }
        item {
            androidx.compose.material3.AssistChip(onClick = onManageRooms, label = { Text(stringResource(if (rooms.isEmpty()) R.string.rooms_create else R.string.nav_rooms)) },
                leadingIcon = { Icon(if (rooms.isEmpty()) Icons.Filled.Add else Icons.Filled.MeetingRoom, null, Modifier.size(18.dp)) })
        }
    }
}

@Composable
private fun QuickChip(d: SavedDeviceEntity, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(d.look(), size = 30.dp)
            Spacer(Modifier.width(8.dp))
            Text(d.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** "Your home" editor: title, layout, which sections and which devices appear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeHomeSheet(settings: AppSettings, devices: List<SavedDeviceEntity>, vm: HomeViewModel, onArrange: () -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(settings.homeTitle) }
    ModalBottomSheet(onDismissRequest = { vm.setTitle(title); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.home_customize), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.home_title_field)) },
                placeholder = { Text(stringResource(R.string.home_title_default)) })
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.home_layout), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val layouts = listOf(HomeLayout.GRID to R.string.layout_grid, HomeLayout.LIST to R.string.layout_list)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                layouts.forEachIndexed { i, (v, res) ->
                    SegmentedButton(settings.homeLayout == v, { vm.setLayout(v) }, SegmentedButtonDefaults.itemShape(i, layouts.size)) { Text(stringResource(res)) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.home_sections), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchLine(stringResource(R.string.home_favorites), settings.homeShowFavorites) { vm.setSections(settings.copy(homeShowFavorites = it)) }
            SwitchLine(stringResource(R.string.home_show_rooms), settings.homeShowRooms) { vm.setSections(settings.copy(homeShowRooms = it)) }
            if (devices.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_devices_on_home), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = onArrange) { Text(stringResource(R.string.home_arrange_short)) }
                }
                devices.forEach { d ->
                    val s = remember(d.customSettings) { d.settings() }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        DeviceAvatar(d.look(), size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(d.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Switch(s.showOnHome, { vm.setShowOnHome(d.id, it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

/** "Add" menu: the three ways to add something, each with a one-line explanation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.action_add), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            AddOption(com.universalir.core.ui.DeviceLook(AppIcons.Remote, com.universalir.core.ui.deviceGradients[0], "ir", 0),
                stringResource(R.string.add_ir_title), stringResource(R.string.add_ir_body)) { onPick(Routes.ADD) }
            AddOption(com.universalir.core.ui.DeviceLook(AppIcons.Wifi, com.universalir.core.ui.deviceGradients[5], "wifi", 5),
                stringResource(R.string.nav_wifi), stringResource(R.string.add_wifi_body)) { onPick(Routes.WIFI) }
            AddOption(com.universalir.core.ui.DeviceLook(AppIcons.Apps, com.universalir.core.ui.deviceGradients[1], "custom", 1),
                stringResource(R.string.quick_custom), stringResource(R.string.add_custom_body)) { onPick(Routes.designer()) }
        }
    }
}

@Composable
private fun AddOption(look: com.universalir.core.ui.DeviceLook, title: String, body: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(look, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Saved Wi-Fi devices as home tiles: same look as IR tiles, tap opens the Wi-Fi remote. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.wifiSection(
    list: List<com.universalir.data.wifi.SavedWifiDevice>, title: String,
    onOpen: (String) -> Unit, onLong: (com.universalir.data.wifi.SavedWifiDevice) -> Unit,
) {
    item(span = { GridItemSpan(maxLineSpan) }) { HomeSection(title, count = list.size) }
    items(list, key = { "w${it.id}" }) { d -> WifiTile(d, { onOpen(d.id) }, { onLong(d) }) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WifiTile(d: com.universalir.data.wifi.SavedWifiDevice, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.heightIn(min = 150.dp).clip(MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), MaterialTheme.shapes.large)
            .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongClick() })) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                com.universalir.feature.wifi.KindBadge(d.kind, 48.dp)
                Spacer(Modifier.weight(1f))
                Icon(AppIcons.Wifi, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.weight(1f))
            Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(com.universalir.feature.wifi.WifiText.kind(d.kind), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Long-press menu for a Wi-Fi device on home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiDeviceSheet(d: com.universalir.data.wifi.SavedWifiDevice, onOpen: () -> Unit, onRename: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    when (step) {
        1 -> com.universalir.core.ui.TextInputDialog(stringResource(R.string.action_rename), d.name, stringResource(R.string.field_name), onRename, onDismiss)
        2 -> com.universalir.core.ui.ConfirmDialog(stringResource(R.string.devices_delete_title), stringResource(R.string.devices_delete_body, d.name),
            stringResource(R.string.action_delete), onRemove, onDismiss, destructive = true)
        else -> ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.universalir.feature.wifi.KindBadge(d.kind, 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(com.universalir.feature.wifi.WifiText.kind(d.kind), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Button(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
                }
                SheetLine(Icons.Filled.Edit, stringResource(R.string.action_rename)) { step = 1 }
                SheetLine(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), MaterialTheme.colorScheme.error) { step = 2 }
            }
        }
    }
}

@Composable
private fun SheetLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint ?: MaterialTheme.colorScheme.onSurface)
    }
}
