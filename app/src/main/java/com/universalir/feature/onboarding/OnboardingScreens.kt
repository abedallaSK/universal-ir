package com.universalir.feature.onboarding
import com.universalir.core.ui.deviceGradients
import com.universalir.core.ui.DeviceLook
import com.universalir.core.ui.DeviceAvatar
import com.universalir.core.ui.AppIcons
import com.universalir.BuildConfig
import androidx.compose.material3.OutlinedButton
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.SegmentedButton
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.universalir.R
import com.universalir.core.ir.HardwareStatus
import com.universalir.core.ir.IrTransmitter
import com.universalir.core.theme.LocalStatusColors
import com.universalir.core.ui.describeHardware
import com.universalir.data.catalog.CatalogSeeder
import com.universalir.data.catalog.SeedResult
import com.universalir.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StartupState(val ready: Boolean = false, val needsOnboarding: Boolean = false, val seed: SeedResult? = null)

@HiltViewModel
class StartupViewModel @Inject constructor(
    private val seeder: CatalogSeeder,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(StartupState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            // Seeding failure is reported on the database page; the app still opens.
            val seed = seeder.seedIfNeeded()
            val s = settings.current()
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < 600) delay(600 - elapsed)
            _state.value = StartupState(true, !s.onboardingDone, seed)
        }
    }
}

@Composable
fun StartupScreen(onReady: (needsOnboarding: Boolean) -> Unit, vm: StartupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.ready) { if (state.ready) onReady(state.needsOnboarding) }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), MaterialTheme.colorScheme.background))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppMark(132.dp)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.startup_tagline), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(Modifier.width(160.dp).clip(CircleShape))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.startup_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** App mark: gradient tile with animated IR waves. */
@Composable
fun AppMark(size: Dp) {
    Box(
        Modifier.size(size).shadow(12.dp, RoundedCornerShape(size * 0.28f)).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF2F6BFF), Color(0xFF06B6D4)))),
        contentAlignment = Alignment.Center,
    ) { IrWaves(Modifier.size(size * 0.7f), Color.White) }
}

/** Subtle animated logo: three arcs pulsing outward from a remote. */
@Composable
fun IrWaves(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "waves")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        val cx = size.width / 2
        val baseY = size.height * 0.72f
        drawRoundRect(color, topLeft = Offset(cx - size.width * 0.12f, baseY - size.height * 0.04f), size = Size(size.width * 0.24f, size.height * 0.3f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f))
        for (i in 0 until 3) {
            val p = ((phase + i / 3f) % 1f)
            val r = size.width * (0.15f + 0.3f * p)
            drawArc(
                color = color.copy(alpha = (1f - p).coerceIn(0f, 1f)),
                startAngle = 225f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(cx - r, baseY - r), size = Size(r * 2, r * 2),
                style = Stroke(width = size.width * 0.045f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val transmitter: IrTransmitter,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _hw = MutableStateFlow<HardwareStatus?>(null)
    val hardware = _hw.asStateFlow()

    fun check() { _hw.value = transmitter.checkHardwareSupport() }
    val prefs = settings.settings
    fun theme(t: com.universalir.data.repository.ThemeMode) = viewModelScope.launch { settings.setTheme(t) }
    fun accent(a: com.universalir.data.repository.AccentTheme) = viewModelScope.launch { settings.setAccent(a) }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        settings.setOnboardingDone(true)
        onDone()
    }
}

private data class OnboardingPage(val look: DeviceLook, val title: Int, val body: Int, val satellites: List<ImageVector>)

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val pages = listOf(
        OnboardingPage(DeviceLook(AppIcons.Remote, deviceGradients[0], "ir", 0), R.string.onb_1_title, R.string.onb_1_body,
            listOf(AppIcons.AirConditioner, AppIcons.Television, AppIcons.Bulb)),
        OnboardingPage(DeviceLook(AppIcons.Wifi, deviceGradients[5], "wifi", 5), R.string.onb_wifi_title, R.string.onb_wifi_body,
            listOf(AppIcons.Television, AppIcons.AirConditioner, AppIcons.TvBox)),
        OnboardingPage(DeviceLook(AppIcons.Apps, deviceGradients[1], "custom", 1), R.string.onb_custom_title, R.string.onb_custom_body,
            listOf(AppIcons.Star, AppIcons.Arrange, AppIcons.Home)),
        OnboardingPage(DeviceLook(AppIcons.Remote, deviceGradients[4], "aim", 4), R.string.onb_3_title, R.string.onb_3_body,
            listOf(AppIcons.Up, AppIcons.Television, AppIcons.Power)),
    )
    // Page 0 is "make it yours" (language, theme, colour); then the feature pages; then the hardware check.
    val pager = rememberPagerState { pages.size + 2 }
    val scope = rememberCoroutineScope()
    val hw by vm.hardware.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val last = pager.currentPage == pages.size + 1

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!last) TextButton(onClick = { vm.finish(onDone) }) { Text(stringResource(R.string.action_skip)) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { index ->
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (index == 0) {
                    PersonalizePage(vm)
                } else if (index - 1 < pages.size) {
                    val p = pages[index - 1]
                    Hero(p.look, p.satellites, tilt = if (index == 4) -20f else 0f)
                    Spacer(Modifier.height(36.dp))
                    Text(stringResource(p.title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(p.body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
                } else {
                    LaunchedEffect(Unit) { vm.check() }
                    val ok = hw is HardwareStatus.Available
                    val c = LocalStatusColors.current
                    Box(Modifier.size(140.dp).clip(CircleShape).background((if (ok) c.success else c.warning).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, Modifier.size(72.dp), tint = if (ok) c.success else c.warning)
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(stringResource(R.string.onb_check_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(hw?.let { describeHardware(context, it) } ?: stringResource(R.string.startup_loading), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (!ok && hw != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.onb_no_ir_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        // Progress dots
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size + 2) { i ->
                val active = i == pager.currentPage
                val w by animateDpAsState(if (active) 24.dp else 8.dp, label = "dot")
                Box(Modifier.padding(3.dp).size(w, 8.dp).clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (pager.currentPage > 0) OutlinedButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, Modifier.height(54.dp)) {
                Text(stringResource(R.string.action_back))
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (!last) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else vm.finish(onDone) },
                Modifier.height(54.dp).widthIn(min = 150.dp)) {
                Text(stringResource(if (!last) R.string.action_next else R.string.action_get_started), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Page illustration: soft rings, a big gradient badge and three small device bubbles around it. */
@Composable
private fun Hero(look: DeviceLook, satellites: List<ImageVector>, tilt: Float) {
    val t = rememberInfiniteTransition(label = "hero")
    val float by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    val ring = look.gradient.first
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(260.dp).clip(CircleShape).background(ring.copy(alpha = 0.06f)))
        Box(Modifier.size(196.dp).clip(CircleShape).background(ring.copy(alpha = 0.10f)))
        Box(Modifier.graphicsLayer { translationY = float; rotationZ = tilt }) { DeviceAvatar(look, size = 120.dp) }
        val spots = listOf(Offset(-96f, -84f), Offset(100f, -60f), Offset(-70f, 100f))
        satellites.zip(spots).forEachIndexed { i, (icon, o) ->
            Box(Modifier.offset(o.x.dp, (o.y + if (i % 2 == 0) -float else float).dp).size(48.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = ring)
            }
        }
    }
}

/** First page: pick language, theme and accent colour before anything else. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PersonalizePage(vm: OnboardingViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle(initialValue = com.universalir.data.repository.AppSettings())
    val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
        .let { when (it) { "iw" -> "he"; "id" -> "in"; else -> it } }
    val langs = listOf("en" to R.string.lang_en, "ar" to R.string.lang_ar, "he" to R.string.lang_he, "fr" to R.string.lang_fr, "es" to R.string.lang_es,
        "de" to R.string.lang_de, "pt" to R.string.lang_pt, "it" to R.string.lang_it, "ru" to R.string.lang_ru, "tr" to R.string.lang_tr,
        "fa" to R.string.lang_fa, "hi" to R.string.lang_hi, "zh" to R.string.lang_zh, "in" to R.string.lang_id, "ur" to R.string.lang_ur)
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        AppMark(88.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.onb_personal_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.set_language), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            langs.forEach { (tag, res) ->
                androidx.compose.material3.FilterChip(current == tag || (current.isBlank() && tag == "en"), {
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(tag))
                }, label = { Text(stringResource(res)) })
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.set_appearance), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        val themes = listOf(com.universalir.data.repository.ThemeMode.SYSTEM to R.string.set_system, com.universalir.data.repository.ThemeMode.LIGHT to R.string.set_light,
            com.universalir.data.repository.ThemeMode.DARK to R.string.set_dark)
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            themes.forEachIndexed { i, (t, res) ->
                SegmentedButton(prefs.theme == t, { vm.theme(t) }, androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, themes.size)) {
                    Text(stringResource(res))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(
                com.universalir.data.repository.AccentTheme.BLUE to Color(0xFF2F6BFF), com.universalir.data.repository.AccentTheme.TEAL to Color(0xFF00897B),
                com.universalir.data.repository.AccentTheme.VIOLET to Color(0xFF6D4AFF), com.universalir.data.repository.AccentTheme.ORANGE to Color(0xFFE8590C),
                com.universalir.data.repository.AccentTheme.ROSE to Color(0xFFD6336C), com.universalir.data.repository.AccentTheme.GREEN to Color(0xFF2B8A3E),
            ).forEach { (a, c) ->
                Box(Modifier.size(40.dp).clip(CircleShape).background(c).clickable { vm.accent(a) }, contentAlignment = Alignment.Center) {
                    if (prefs.accent == a) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
