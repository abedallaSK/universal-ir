package com.universalir.data.repository

import com.universalir.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** The manifest published next to the APK, e.g. docs/update.json uploaded to the developer's site. */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    /** Where the user downloads the new APK (opened in the browser). */
    val url: String,
    val notes: String = "",
)

sealed interface AppUpdateResult {
    data object UpToDate : AppUpdateResult
    data class Available(val manifest: UpdateManifest) : AppUpdateResult
    data class Failed(val reason: String) : AppUpdateResult
}

/** Checks the developer's site for a newer version. Only runs when the user taps "Check for updates". */
@Singleton
class UpdateChecker @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): AppUpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode !in 200..299) return@runCatching AppUpdateResult.Failed("HTTP ${conn.responseCode}")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val m = json.decodeFromString(UpdateManifest.serializer(), text)
                // Only https download links are offered.
                if (!m.url.startsWith("https://")) return@runCatching AppUpdateResult.Failed("insecure url")
                if (m.versionCode > BuildConfig.VERSION_CODE) AppUpdateResult.Available(m) else AppUpdateResult.UpToDate
            } finally { conn.disconnect() }
        }.getOrElse { AppUpdateResult.Failed(it.message ?: it.javaClass.simpleName) }
    }

    companion object {
        const val MANIFEST_URL = "https://abedallask.netlify.app/universal-ir/update.json"
        const val DEVELOPER_URL = "https://abedallask.netlify.app/"
    }
}
