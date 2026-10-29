package com.universalir.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.universalir.ir.protocol.ac.AcMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ButtonSize { SMALL, MEDIUM, LARGE }
enum class RemoteLayout { COMPACT, EXPANDED }
enum class TemperatureUnit { CELSIUS, FAHRENHEIT }
enum class HomeLayout { GRID, LIST }

/** Which kinds of remote the app offers. AUTO = infrared only when the phone has an IR emitter. */
enum class ConnectionMode { AUTO, BOTH, WIFI_ONLY }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val haptics: Boolean = true,
    val sound: Boolean = false,
    val buttonSize: ButtonSize = ButtonSize.MEDIUM,
    val remoteLayout: RemoteLayout = RemoteLayout.EXPANDED,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val defaultAcTemp: Int = 24,
    val defaultAcMode: AcMode = AcMode.COOL,
    val updateUrl: String = "",
    val catalogSeedVersion: Int = 0,
    val recentBrands: List<String> = emptyList(),
    val savedDevicesAsGrid: Boolean = true,
    /** Custom home page: user-chosen title (blank = default), layout and visible sections. */
    val homeTitle: String = "",
    val homeLayout: HomeLayout = HomeLayout.GRID,
    val homeShowQuick: Boolean = true,
    val homeShowFavorites: Boolean = true,
    val homeShowRecent: Boolean = true,
    val homeShowRooms: Boolean = true,
    val remoteStyle: com.universalir.domain.model.RemoteStyle = com.universalir.domain.model.RemoteStyle.CLASSIC,
    val connectionMode: ConnectionMode = ConnectionMode.AUTO,
) {
    /** Whether infrared features are shown, given whether this phone has a usable emitter. */
    fun irEnabled(hasEmitter: Boolean): Boolean = when (connectionMode) {
        ConnectionMode.AUTO -> hasEmitter
        ConnectionMode.BOTH -> true
        ConnectionMode.WIFI_ONLY -> false
    }
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme")
        val haptics = booleanPreferencesKey("haptics")
        val sound = booleanPreferencesKey("sound")
        val buttonSize = stringPreferencesKey("button_size")
        val layout = stringPreferencesKey("remote_layout")
        val unit = stringPreferencesKey("temp_unit")
        val acTemp = intPreferencesKey("default_ac_temp")
        val acMode = stringPreferencesKey("default_ac_mode")
        val updateUrl = stringPreferencesKey("update_url")
        val seed = intPreferencesKey("catalog_seed_version")
        val recentBrands = stringPreferencesKey("recent_brands")
        val grid = booleanPreferencesKey("saved_grid")
        val homeTitle = stringPreferencesKey("home_title")
        val homeLayout = stringPreferencesKey("home_layout")
        val homeQuick = booleanPreferencesKey("home_quick")
        val homeFavorites = booleanPreferencesKey("home_favorites")
        val homeRecent = booleanPreferencesKey("home_recent")
        val homeRooms = booleanPreferencesKey("home_rooms")
        val remoteStyle = stringPreferencesKey("remote_style")
        val connectionMode = stringPreferencesKey("connection_mode")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        onboardingDone = this[K.onboarding] ?: false,
        theme = enumOr(this[K.theme], ThemeMode.SYSTEM),
        haptics = this[K.haptics] ?: true,
        sound = this[K.sound] ?: false,
        buttonSize = enumOr(this[K.buttonSize], ButtonSize.MEDIUM),
        remoteLayout = enumOr(this[K.layout], RemoteLayout.EXPANDED),
        temperatureUnit = enumOr(this[K.unit], TemperatureUnit.CELSIUS),
        defaultAcTemp = this[K.acTemp] ?: 24,
        defaultAcMode = enumOr(this[K.acMode], AcMode.COOL),
        updateUrl = this[K.updateUrl] ?: "",
        catalogSeedVersion = this[K.seed] ?: 0,
        recentBrands = this[K.recentBrands]?.split('\u001F')?.filter { it.isNotBlank() } ?: emptyList(),
        savedDevicesAsGrid = this[K.grid] ?: true,
        homeTitle = this[K.homeTitle] ?: "",
        homeLayout = enumOr(this[K.homeLayout], HomeLayout.GRID),
        homeShowQuick = this[K.homeQuick] ?: true,
        homeShowFavorites = this[K.homeFavorites] ?: true,
        homeShowRecent = this[K.homeRecent] ?: true,
        homeShowRooms = this[K.homeRooms] ?: true,
        remoteStyle = enumOr(this[K.remoteStyle], com.universalir.domain.model.RemoteStyle.CLASSIC),
        connectionMode = enumOr(this[K.connectionMode], ConnectionMode.AUTO),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[K.onboarding] = done }
    suspend fun setTheme(t: ThemeMode) = context.dataStore.edit { it[K.theme] = t.name }
    suspend fun setHaptics(v: Boolean) = context.dataStore.edit { it[K.haptics] = v }
    suspend fun setSound(v: Boolean) = context.dataStore.edit { it[K.sound] = v }
    suspend fun setButtonSize(v: ButtonSize) = context.dataStore.edit { it[K.buttonSize] = v.name }
    suspend fun setRemoteLayout(v: RemoteLayout) = context.dataStore.edit { it[K.layout] = v.name }
    suspend fun setTemperatureUnit(v: TemperatureUnit) = context.dataStore.edit { it[K.unit] = v.name }
    suspend fun setDefaultAc(temp: Int, mode: AcMode) = context.dataStore.edit { it[K.acTemp] = temp; it[K.acMode] = mode.name }
    suspend fun setUpdateUrl(url: String) = context.dataStore.edit { it[K.updateUrl] = url.trim() }
    suspend fun setCatalogSeedVersion(v: Int) = context.dataStore.edit { it[K.seed] = v }
    suspend fun setSavedDevicesAsGrid(v: Boolean) = context.dataStore.edit { it[K.grid] = v }

    suspend fun setConnectionMode(v: ConnectionMode) = context.dataStore.edit { it[K.connectionMode] = v.name }
    suspend fun setRemoteStyle(v: com.universalir.domain.model.RemoteStyle) = context.dataStore.edit { it[K.remoteStyle] = v.name }
    suspend fun setHomeTitle(v: String) = context.dataStore.edit { it[K.homeTitle] = v.trim() }
    suspend fun setHomeLayout(v: HomeLayout) = context.dataStore.edit { it[K.homeLayout] = v.name }
    suspend fun setHomeSections(quick: Boolean, favorites: Boolean, recent: Boolean, rooms: Boolean) = context.dataStore.edit {
        it[K.homeQuick] = quick; it[K.homeFavorites] = favorites; it[K.homeRecent] = recent; it[K.homeRooms] = rooms
    }

    suspend fun addRecentBrand(brandId: String) = context.dataStore.edit { prefs ->
        val list = prefs[K.recentBrands]?.split('\u001F')?.filter { it.isNotBlank() } ?: emptyList()
        prefs[K.recentBrands] = (listOf(brandId) + list.filter { it != brandId }).take(8).joinToString("\u001F")
    }
}
