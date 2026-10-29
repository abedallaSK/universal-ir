package com.universalir.app.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.universalir.feature.about.AboutScreen
import com.universalir.feature.acremote.AcRemoteScreen
import com.universalir.feature.codeexplorer.CodeExplorerScreen
import com.universalir.feature.codeexplorer.CodeSetDetailScreen
import com.universalir.feature.codewizard.CodeWizardScreen
import com.universalir.feature.customremote.CustomRemoteDesignerScreen
import com.universalir.feature.database.DatabaseScreen
import com.universalir.feature.devicebrowser.AddDeviceScreen
import com.universalir.feature.devicebrowser.BrandSelectionScreen
import com.universalir.feature.devicebrowser.ModelSelectionScreen
import com.universalir.feature.devices.SavedDevicesScreen
import com.universalir.feature.diagnostics.HardwareDiagnosticsScreen
import com.universalir.feature.diagnostics.ProtocolDiagnosticsScreen
import com.universalir.feature.favorites.FavoritesScreen
import com.universalir.feature.home.HomeScreen
import com.universalir.feature.onboarding.OnboardingScreen
import com.universalir.feature.onboarding.StartupScreen
import com.universalir.feature.remote.CommandRemoteScreen
import com.universalir.feature.remote.LayoutEditorScreen
import com.universalir.feature.remote.RemoteRouterScreen
import com.universalir.feature.rooms.RoomDetailScreen
import com.universalir.feature.rooms.RoomsScreen
import com.universalir.feature.settings.SettingsScreen
import com.universalir.feature.wifi.WifiDiscoveryScreen
import com.universalir.feature.wifi.WifiRemoteScreen

object Routes {
    const val STARTUP = "startup"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val HARDWARE = "hardware"
    const val ADD = "add"
    const val BRANDS = "brands/{category}"
    const val MODELS = "models/{category}/{brandId}"
    const val WIZARD = "wizard/{category}/{brandId}?modelId={modelId}"
    const val REMOTE = "remote/{deviceId}"
    const val AC_REMOTE = "ac/{deviceId}"
    const val CMD_REMOTE = "cmd/{deviceId}"
    const val DEVICES = "devices"
    const val ROOMS = "rooms"
    const val LAYOUT = "layout/{deviceId}"
    const val ROOM = "room/{roomId}"
    const val FAVORITES = "favorites"
    const val DESIGNER = "designer?deviceId={deviceId}"
    const val EXPLORER = "explorer"
    const val CODESET = "codeset/{codeSetId}"
    const val PROTOCOLS = "protocols?protocolId={protocolId}"
    const val DATABASE = "database"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val WIFI = "wifi"
    const val WIFI_REMOTE = "wifi/{wifiId}"

    fun brands(category: String) = "brands/$category"
    fun models(category: String, brandId: String) = "models/$category/${Uri.encode(brandId)}"
    fun wizard(category: String, brandId: String, modelId: String? = null) =
        "wizard/$category/${Uri.encode(brandId)}" + (modelId?.let { "?modelId=${Uri.encode(it)}" } ?: "")
    fun remote(deviceId: Long) = "remote/$deviceId"
    fun room(roomId: Long) = "room/$roomId"
    fun layout(deviceId: Long) = "layout/$deviceId"
    fun wifiRemote(id: String) = "wifi/${Uri.encode(id)}"
    fun acRemote(deviceId: Long) = "ac/$deviceId"
    fun cmdRemote(deviceId: Long) = "cmd/$deviceId"
    fun designer(deviceId: Long? = null) = "designer" + (deviceId?.let { "?deviceId=$it" } ?: "")
    fun codeSet(id: String) = "codeset/${Uri.encode(id)}"
    fun protocols(protocolId: String? = null) = "protocols" + (protocolId?.let { "?protocolId=${Uri.encode(it)}" } ?: "")
}

@Composable
fun AppNavHost(nav: NavHostController = rememberNavController(), openDevice: Long? = null, onDeviceOpened: () -> Unit = {}) {
    val back: () -> Unit = { nav.popBackStack() }
    // Widget/shortcut launches: wait until startup has landed on home, then open the device remote on top of it.
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(openDevice, entry?.destination?.route) {
        val route = entry?.destination?.route
        if (openDevice != null && route != null && route != Routes.STARTUP && route != Routes.ONBOARDING) {
            nav.navigate(Routes.remote(openDevice)) { launchSingleTop = true }
            onDeviceOpened()
        }
    }
    NavHost(
        navController = nav,
        startDestination = Routes.STARTUP,
        enterTransition = { fadeIn() + slideInHorizontally { it / 12 } },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() + slideOutHorizontally { it / 12 } },
    ) {
        composable(Routes.STARTUP) {
            StartupScreen(onReady = { onboarding ->
                nav.navigate(if (onboarding) Routes.ONBOARDING else Routes.HOME) { popUpTo(Routes.STARTUP) { inclusive = true } }
            })
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.HARDWARE) { HardwareDiagnosticsScreen(onBack = back) }
        composable(Routes.ADD) {
            AddDeviceScreen(
                onCategory = { nav.navigate(Routes.brands(it.name)) },
                onCustom = { nav.navigate(Routes.designer()) },
                onImport = { nav.navigate(Routes.DATABASE) },
                onWifi = { nav.navigate(Routes.WIFI) },
                onBack = back,
            )
        }
        composable(Routes.BRANDS, listOf(navArgument("category") { type = NavType.StringType })) {
            BrandSelectionScreen(
                onBrand = { cat, brandId -> nav.navigate(Routes.models(cat, brandId)) },
                onBack = back,
            )
        }
        composable(Routes.MODELS, listOf(navArgument("category") { type = NavType.StringType }, navArgument("brandId") { type = NavType.StringType })) {
            ModelSelectionScreen(
                onSaved = { id -> nav.navigate(Routes.remote(id)) { popUpTo(Routes.HOME) } },
                onWizard = { cat, brand, model -> nav.navigate(Routes.wizard(cat, brand, model)) },
                onCodeSet = { nav.navigate(Routes.codeSet(it)) },
                onBack = back,
            )
        }
        composable(
            Routes.WIZARD,
            listOf(
                navArgument("category") { type = NavType.StringType },
                navArgument("brandId") { type = NavType.StringType },
                navArgument("modelId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            CodeWizardScreen(onSaved = { id -> nav.navigate(Routes.remote(id)) { popUpTo(Routes.HOME) } }, onBack = back)
        }
        composable(Routes.REMOTE, listOf(navArgument("deviceId") { type = NavType.LongType })) {
            RemoteRouterScreen(
                onAc = { id -> nav.navigate(Routes.acRemote(id)) { popUpTo(Routes.REMOTE) { inclusive = true } } },
                onCommands = { id -> nav.navigate(Routes.cmdRemote(id)) { popUpTo(Routes.REMOTE) { inclusive = true } } },
                onBack = back,
            )
        }
        composable(Routes.AC_REMOTE, listOf(navArgument("deviceId") { type = NavType.LongType })) {
            AcRemoteScreen(onBack = back, onDiagnostics = { nav.navigate(Routes.protocols(it)) })
        }
        composable(Routes.CMD_REMOTE, listOf(navArgument("deviceId") { type = NavType.LongType })) {
            CommandRemoteScreen(onBack = back, onDesigner = { nav.navigate(Routes.designer(it)) }, onDiagnostics = { nav.navigate(Routes.protocols()) },
                onEditLayout = { nav.navigate(Routes.layout(it)) })
        }
        composable(Routes.DEVICES) { SavedDevicesScreen(onOpen = { nav.navigate(Routes.remote(it)) }, onAdd = { nav.navigate(Routes.ADD) }, onBack = back) }
        composable(Routes.ROOMS) { RoomsScreen(onOpenRoom = { nav.navigate(Routes.room(it)) }, onBack = back) }
        composable(Routes.ROOM, listOf(navArgument("roomId") { type = NavType.LongType })) {
            RoomDetailScreen(onOpenDevice = { nav.navigate(Routes.remote(it)) }, onBack = back)
        }
        composable(Routes.FAVORITES) { FavoritesScreen(onOpenDevice = { nav.navigate(Routes.remote(it)) }, onBack = back) }
        composable(Routes.DESIGNER, listOf(navArgument("deviceId") { type = NavType.LongType; defaultValue = -1L })) {
            CustomRemoteDesignerScreen(onDone = { id -> nav.navigate(Routes.remote(id)) { popUpTo(Routes.HOME) } }, onBack = back)
        }
        composable(Routes.EXPLORER) { CodeExplorerScreen(onCodeSet = { nav.navigate(Routes.codeSet(it)) }, onBack = back) }
        composable(Routes.CODESET, listOf(navArgument("codeSetId") { type = NavType.StringType })) {
            CodeSetDetailScreen(onBack = back, onProtocol = { nav.navigate(Routes.protocols(it)) })
        }
        composable(Routes.PROTOCOLS, listOf(navArgument("protocolId") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            ProtocolDiagnosticsScreen(onBack = back)
        }
        composable(Routes.DATABASE) { DatabaseScreen(onBack = back) }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = back,
                onDiagnostics = { nav.navigate(Routes.HARDWARE) },
                onDatabase = { nav.navigate(Routes.DATABASE) },
                onAbout = { nav.navigate(Routes.ABOUT) },
                onProtocols = { nav.navigate(Routes.protocols()) },
                onExplorer = { nav.navigate(Routes.EXPLORER) },
                onOnboarding = { nav.navigate(Routes.ONBOARDING) },
            )
        }
        composable(Routes.ABOUT) { AboutScreen(onBack = back) }
        composable(Routes.LAYOUT, listOf(navArgument("deviceId") { type = NavType.LongType })) { LayoutEditorScreen(onBack = back) }
        composable(Routes.WIFI) { WifiDiscoveryScreen(onBack = back, onOpen = { nav.navigate(Routes.wifiRemote(it)) }) }
        composable(Routes.WIFI_REMOTE, listOf(navArgument("wifiId") { type = NavType.StringType })) { WifiRemoteScreen(onBack = back) }
    }
}
