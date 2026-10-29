package com.universalir.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.BuildConfig
import com.universalir.R
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.AppIcons
import com.universalir.core.ui.AppScaffold
import com.universalir.core.ui.readableWidth
import com.universalir.data.repository.AppSettings
import com.universalir.data.repository.ButtonSize
import com.universalir.data.repository.RemoteLayout
import com.universalir.data.repository.SettingsRepository
import com.universalir.data.repository.TemperatureUnit
import com.universalir.data.repository.ThemeMode
import com.universalir.data.repository.UpdateChecker
import com.universalir.data.repository.AppUpdateResult
import com.universalir.feature.acremote.modeIcon
import com.universalir.feature.acremote.modeName
import com.universalir.feature.onboarding.AppMark
import com.universalir.ir.protocol.ac.AcMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** null = not checked yet; "checking" is modelled by [SettingsViewModel.checking]. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val updates: UpdateChecker,
    transmitter: com.universalir.core.ir.IrTransmitter,
) : ViewModel() {
    /** Checked once: whether this phone has a usable IR emitter. */
    val hasEmitter: Boolean = transmitter.checkHardwareSupport() is com.universalir.core.ir.HardwareStatus.Available
    fun connection(m: com.universalir.data.repository.ConnectionMode) = viewModelScope.launch { repo.setConnectionMode(m) }
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings(onboardingDone = true))
    val checking = MutableStateFlow(false)
    val update = MutableStateFlow<AppUpdateResult?>(null)

    fun theme(t: ThemeMode) = viewModelScope.launch { repo.setTheme(t) }
    fun accent(a: com.universalir.data.repository.AccentTheme) = viewModelScope.launch { repo.setAccent(a) }
    fun pureBlack(v: Boolean) = viewModelScope.launch { repo.setPureBlack(v) }
    fun customAccent(argb: Long) = viewModelScope.launch { repo.setCustomAccent(argb) }
    fun irRepeat(v: Int) = viewModelScope.launch { repo.setIrRepeat(v) }
    fun style(v: com.universalir.domain.model.RemoteStyle) = viewModelScope.launch { repo.setRemoteStyle(v) }
    fun haptics(v: Boolean) = viewModelScope.launch { repo.setHaptics(v) }
    fun sound(v: Boolean) = viewModelScope.launch { repo.setSound(v) }
    fun size(v: ButtonSize) = viewModelScope.launch { repo.setButtonSize(v) }
    fun layout(v: RemoteLayout) = viewModelScope.launch { repo.setRemoteLayout(v) }
    fun unit(v: TemperatureUnit) = viewModelScope.launch { repo.setTemperatureUnit(v) }
    fun defaultAc(temp: Int, mode: AcMode) = viewModelScope.launch { repo.setDefaultAc(temp.coerceIn(16, 32), mode) }

    fun checkUpdates() = viewModelScope.launch {
        checking.value = true
        update.value = updates.check()
        checking.value = false
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit, onDiagnostics: () -> Unit, onDatabase: () -> Unit, onAbout: () -> Unit,
    onProtocols: () -> Unit, onOnboarding: () -> Unit, onExplorer: () -> Unit, vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').ifBlank { "system" }
        .let { when (it) { "iw" -> "he"; "id" -> "in"; else -> it } }
    var privacyOpen by remember { mutableStateOf(false) }
    var stylePicker by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf(false) }
    var languagePicker by remember { mutableStateOf(false) }
    if (colorPicker) ColorPickerSheet(s.customAccent, onSave = vm::customAccent, onDismiss = { colorPicker = false })
    if (languagePicker) LanguageSheet(onDismiss = { languagePicker = false })
    if (stylePicker) com.universalir.feature.remote.RemoteStyleSheet(s.remoteStyle, onPick = { st, _ -> vm.style(st); stylePicker = false }, onDismiss = { stylePicker = false })

    AppScaffold(stringResource(R.string.nav_settings), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(720.dp), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // App card: version + update check
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppMark(56.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(R.string.about_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        UpdateRow(checking, update, onCheck = vm::checkUpdates, onDownload = openUrl)
                    }
                }
            }
            // Appearance
            item {
                Group(stringResource(R.string.set_appearance), Icons.Filled.Palette) {
                    ThemePreview()
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeCard(ThemeMode.LIGHT, R.string.set_light, s.theme, Modifier.weight(1f)) { vm.theme(it) }
                        ThemeCard(ThemeMode.DARK, R.string.set_dark, s.theme, Modifier.weight(1f)) { vm.theme(it) }
                        ThemeCard(ThemeMode.SYSTEM, R.string.set_system, s.theme, Modifier.weight(1f)) { vm.theme(it) }
                    }
                    Divider()
                    AccentPicker(s.accent, s.customAccent, onPick = vm::accent, onCustom = { colorPicker = true })
                    Divider()
                    SwitchLine(Icons.Filled.DarkMode, stringResource(R.string.set_pure_black), s.pureBlack, vm::pureBlack)
                    Divider()
                    LinkLine(Icons.Filled.Palette, stringResource(R.string.style_title), stringResource(com.universalir.feature.remote.remoteStyleName(s.remoteStyle))) { stylePicker = true }
                    Divider()
                    Segmented(stringResource(R.string.set_button_size), listOf(ButtonSize.SMALL to R.string.set_small, ButtonSize.MEDIUM to R.string.set_medium,
                        ButtonSize.LARGE to R.string.set_large), s.buttonSize) { vm.size(it) }
                    Divider()
                    Segmented(stringResource(R.string.set_layout), listOf(RemoteLayout.COMPACT to R.string.set_compact, RemoteLayout.EXPANDED to R.string.set_expanded),
                        s.remoteLayout) { vm.layout(it) }
                }
            }
            // Language
            item {
                Group(stringResource(R.string.set_language), Icons.Filled.Language) {
                    val tag = currentAppLanguage()
                    val name = appLanguages.firstOrNull { it.first == tag }?.let { stringResource(it.second) } ?: stringResource(R.string.set_system)
                    LinkLine(Icons.Filled.Language, name, stringResource(R.string.lang_change), onClick = { languagePicker = true })
                }
            }
            // Feedback
            item {
                Group(stringResource(R.string.set_feedback), Icons.Filled.TouchApp) {
                    SwitchLine(Icons.Filled.Vibration, stringResource(R.string.set_haptics), s.haptics, vm::haptics)
                    Divider()
                    SwitchLine(Icons.Filled.VolumeUp, stringResource(R.string.set_sound), s.sound, vm::sound)
                }
            }
            // Remote type: infrared, Wi-Fi or both
            item {
                Group(stringResource(R.string.set_remote_type), AppIcons.Remote) {
                    val ok = vm.hasEmitter
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val c = if (ok) LocalStatusColors.current.success else LocalStatusColors.current.warning
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(if (ok) R.string.rt_ir_found else R.string.rt_ir_missing), style = MaterialTheme.typography.bodyMedium, color = c)
                    }
                    Divider()
                    val modes = listOf(
                        Triple(com.universalir.data.repository.ConnectionMode.AUTO, R.string.rt_auto, if (ok) R.string.rt_auto_ir else R.string.rt_auto_noir),
                        Triple(com.universalir.data.repository.ConnectionMode.BOTH, R.string.rt_both, R.string.rt_both_desc),
                        Triple(com.universalir.data.repository.ConnectionMode.WIFI_ONLY, R.string.rt_wifi, R.string.rt_wifi_desc),
                    )
                    modes.forEachIndexed { i, (m, title, desc) ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().clickable { vm.connection(m) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(s.connectionMode == m, null)
                        }
                    }
                    Divider()
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.set_ir_repeat), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.set_ir_repeat_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            (1..5).forEachIndexed { i, n ->
                                SegmentedButton(s.irRepeat == n, { vm.irRepeat(n) }, SegmentedButtonDefaults.itemShape(i, 5)) { Text("${n}×") }
                            }
                        }
                    }
                }
            }
            // Air conditioner defaults
            item {
                Group(stringResource(R.string.cat_ac), Icons.Filled.Thermostat) {
                    Segmented(stringResource(R.string.set_units), listOf(TemperatureUnit.CELSIUS to R.string.set_celsius, TemperatureUnit.FAHRENHEIT to R.string.set_fahrenheit_short),
                        s.temperatureUnit) { vm.unit(it) }
                    Divider()
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.set_default_ac), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(10.dp))
                        val modes = listOf(AcMode.COOL, AcMode.HEAT, AcMode.AUTO, AcMode.DRY, AcMode.FAN_ONLY)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { i, m ->
                                SegmentedButton(s.defaultAcMode == m, { vm.defaultAc(s.defaultAcTemp, m) }, SegmentedButtonDefaults.itemShape(i, modes.size),
                                    icon = {}) { Icon(modeIcon(m), stringResource(modeName(m)), Modifier.size(20.dp)) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(modeName(s.defaultAcMode)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { vm.defaultAc(s.defaultAcTemp - 1, s.defaultAcMode) }, shape = CircleShape, contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(42.dp), enabled = s.defaultAcTemp > 16) { Icon(AppIcons.Minus, null, Modifier.size(18.dp)) }
                            Text(com.universalir.core.ui.formatTemp(s.defaultAcTemp, s.temperatureUnit), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                            OutlinedButton(onClick = { vm.defaultAc(s.defaultAcTemp + 1, s.defaultAcMode) }, shape = CircleShape, contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(42.dp), enabled = s.defaultAcTemp < 32) { Icon(AppIcons.Plus, null, Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            // Help
            item {
                Group(stringResource(R.string.set_help), Icons.Filled.School) {
                    LinkLine(Icons.Filled.School, stringResource(R.string.set_onboarding), onClick = onOnboarding)
                    Divider()
                    LinkLine(Icons.Filled.Build, stringResource(R.string.nav_hardware), onClick = onDiagnostics)
                    Divider()
                    LinkLine(Icons.Filled.PrivacyTip, stringResource(R.string.set_privacy), onClick = { privacyOpen = !privacyOpen })
                    AnimatedVisibility(privacyOpen) {
                        Text(stringResource(R.string.set_privacy_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 64.dp, end = 16.dp, bottom = 14.dp))
                    }
                }
            }
            // Advanced
            item {
                Group(stringResource(R.string.set_advanced), Icons.Filled.Code) {
                    LinkLine(Icons.Filled.Storage, stringResource(R.string.nav_database), stringResource(R.string.set_db_hint), onDatabase)
                    Divider()
                    LinkLine(Icons.Filled.ManageSearch, stringResource(R.string.nav_explorer), onClick = onExplorer)
                    Divider()
                    LinkLine(Icons.Filled.Insights, stringResource(R.string.nav_protocols), onClick = onProtocols)
                    Divider()
                    LinkLine(AppIcons.Info, stringResource(R.string.nav_about), onClick = onAbout)
                    Divider()
                    LinkLine(Icons.Filled.Code, stringResource(R.string.set_github), stringResource(R.string.set_github_desc),
                        onClick = { openUrl(UpdateChecker.REPO_URL) })
                }
            }
            // Developer
            item { DeveloperCard(onClick = { openUrl(UpdateChecker.DEVELOPER_URL) }) }
        }
    }
}

@Composable
private fun UpdateRow(checking: Boolean, update: AppUpdateResult?, onCheck: () -> Unit, onDownload: (String) -> Unit) {
    val status = LocalStatusColors.current
    when {
        checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.upd_checking), style = MaterialTheme.typography.bodyMedium)
        }
        update is AppUpdateResult.Available -> Column {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.upd_available, update.manifest.versionName), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (update.manifest.notes.isNotBlank()) Text(update.manifest.notes.replace(Regex("[#*>`]"), "").trim(), maxLines = 8,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onDownload(update.manifest.url) }, Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Filled.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.upd_download))
            }
        }
        else -> Column {
            when (update) {
                AppUpdateResult.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Icon(Icons.Filled.Check, null, tint = status.success, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.upd_latest), style = MaterialTheme.typography.bodyMedium, color = status.success)
                }
                is AppUpdateResult.Failed -> Text(stringResource(R.string.upd_failed), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 10.dp))
                else -> {}
            }
            OutlinedButton(onClick = onCheck, Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Filled.SystemUpdate, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.set_check_updates))
            }
        }
    }
}

/** Titled card holding a group of rows. */
@Composable
private fun Group(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) { Column { content() } }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
private fun IconTile(icon: ImageVector) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun SwitchLine(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

@Composable
private fun LinkLine(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> Segmented(title: String, options: List<Pair<T, Int>>, selected: T, onPick: (T) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (v, res) ->
                SegmentedButton(selected == v, { onPick(v) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(stringResource(res), maxLines = 1) }
            }
        }
    }
}

/** Mini mock-up of the app in each theme, so the choice is visual. */
@Composable
private fun ThemeCard(mode: ThemeMode, label: Int, selected: ThemeMode, modifier: Modifier, onPick: (ThemeMode) -> Unit) {
    val isSel = mode == selected
    Surface(onClick = { onPick(mode) }, modifier = modifier, shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (isSel) 2.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp))) {
                if (mode != ThemeMode.DARK) MiniScreen(Color(0xFFF6F7F9), Color(0xFFFFFFFF), Color(0xFF0E1116), Modifier.weight(1f))
                if (mode != ThemeMode.LIGHT) MiniScreen(Color(0xFF0E1116), Color(0xFF1F242D), Color(0xFFE6E9EF), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSel) { Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun MiniScreen(bg: Color, card: Color, ink: Color, modifier: Modifier) {
    Column(modifier.fillMaxSize().background(bg).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth(0.6f).height(5.dp).clip(CircleShape).background(ink.copy(alpha = 0.7f)))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(2) {
                Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(6.dp)).background(card)) {
                    Box(Modifier.padding(4.dp).size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2F6BFF)))
                }
            }
        }
    }
}

@Composable
private fun DeveloperCard(onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text("AK", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.about_made_by), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(DEVELOPER_NAME, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.about_author_role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.about_website), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The developer's name stays in Latin letters in every language. */
const val DEVELOPER_NAME = "Abdullah Sheikh Khalil"

