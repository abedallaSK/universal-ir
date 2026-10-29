package com.universalir

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universalir.app.navigation.AppNavHost
import com.universalir.core.theme.UniversalIrTheme
import com.universalir.core.theme.isDark
import com.universalir.core.ui.LocalAppSettings
import com.universalir.data.repository.SettingsRepository
import com.universalir.data.repository.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** AppCompatActivity so per-app language (Arabic/Hebrew/English) works below API 33 too. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    /** Device to open once the app reaches home; set by widgets and shortcuts via [EXTRA_DEVICE_ID]. */
    private val openDevice = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Read the stored theme before the first frame so window, splash and system bars match the
        // app theme instead of the system one (a DataStore read is a few ms; it runs once per launch).
        val initial = runBlocking { settingsRepository.settings.first() }
        applyNightMode(initial.theme)
        readDeviceExtra(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = initial)
            val dark = settings.theme.isDark()
            DisposableEffect(settings.theme, dark) {
                applyNightMode(settings.theme)
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            CompositionLocalProvider(LocalAppSettings provides settings) {
                UniversalIrTheme(themeMode = settings.theme, buttonSize = settings.buttonSize) {
                    val pending by openDevice.collectAsStateWithLifecycle()
                    // One themed surface under every screen: sets the default text colour (onBackground),
                    // so screens without their own Scaffold/Surface never fall back to black text in dark mode.
                    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                        AppNavHost(openDevice = pending, onDeviceOpened = { openDevice.value = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDeviceExtra(intent)
    }

    private fun readDeviceExtra(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_DEVICE_ID, -1L) ?: -1L
        if (id > 0) openDevice.value = id
        intent?.removeExtra(EXTRA_DEVICE_ID)
    }

    companion object {
        const val EXTRA_DEVICE_ID = "com.universalir.extra.DEVICE_ID"
    }

    private fun applyNightMode(theme: ThemeMode) {
        val mode = when (theme) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }
}
