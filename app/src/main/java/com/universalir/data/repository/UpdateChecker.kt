package com.universalir.data.repository

import com.universalir.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** What the update check found: the newest release on GitHub. */
data class UpdateManifest(
    val versionName: String,
    /** Direct APK download if the release has one, else the release page (opened in the browser). */
    val url: String,
    val notes: String = "",
    val pageUrl: String = url,
)

sealed interface AppUpdateResult {
    data object UpToDate : AppUpdateResult
    data class Available(val manifest: UpdateManifest) : AppUpdateResult
    data class Failed(val reason: String) : AppUpdateResult
}

@Serializable
private data class GitHubAsset(val name: String, @SerialName("browser_download_url") val downloadUrl: String)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

/** Checks the project's GitHub releases for a newer version. Only runs when the user taps "Check for updates". */
@Singleton
class UpdateChecker @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): AppUpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "UniversalIR/${BuildConfig.VERSION_NAME}")
            try {
                if (conn.responseCode == 404) return@runCatching AppUpdateResult.UpToDate // no release published yet
                if (conn.responseCode !in 200..299) return@runCatching AppUpdateResult.Failed("HTTP ${conn.responseCode}")
                val release = json.decodeFromString(GitHubRelease.serializer(), conn.inputStream.bufferedReader().use { it.readText() })
                val latest = release.tag.removePrefix("v")
                if (!isNewer(latest, BuildConfig.VERSION_NAME)) return@runCatching AppUpdateResult.UpToDate
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }?.downloadUrl
                AppUpdateResult.Available(UpdateManifest(latest, apk ?: release.htmlUrl, release.body.orEmpty().trim(), release.htmlUrl))
            } finally { conn.disconnect() }
        }.getOrElse { AppUpdateResult.Failed(it.message ?: it.javaClass.simpleName) }
    }

    companion object {
        const val REPO = "abedallaSK/universal-ir"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
        const val REPO_URL = "https://github.com/$REPO"
        const val DEVELOPER_URL = "https://abedallask.netlify.app/"

        /** Compares dotted versions numerically ("0.10.0" > "0.9.2"); suffixes like "-debug" are ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
