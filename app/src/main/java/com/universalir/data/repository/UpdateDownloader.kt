package com.universalir.data.repository

import com.universalir.ir.format.CanonicalCodeSet
import com.universalir.ir.format.CanonicalImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** A downloadable pack: plain data only, never code. */
@Serializable
data class CodePack(val formatVersion: Int = 1, val name: String = "", val codeSets: List<CanonicalCodeSet>)

sealed interface UpdateResult {
    data class Done(val imported: Int, val duplicates: Int, val rejected: Int) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * Optional, user-initiated database update from an HTTPS URL the user configured. No server is
 * shipped or contacted by default. Payloads are size-limited JSON and every command is
 * re-validated by [CanonicalImporter] before it is stored.
 */
@Singleton
class UpdateDownloader @Inject constructor(private val codes: CodeRepository) {

    suspend fun download(url: String, onProgress: (Long) -> Unit = {}): UpdateResult = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext UpdateResult.Failed("Only https:// URLs are accepted.")
        val bytes = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) return@withContext UpdateResult.Failed("Server answered HTTP ${conn.responseCode}.")
                val declared = conn.contentLengthLong
                if (declared > MAX_BYTES) return@withContext UpdateResult.Failed("Update is larger than ${MAX_BYTES / 1_000_000} MB.")
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return@withContext UpdateResult.Failed("Update is larger than ${MAX_BYTES / 1_000_000} MB.")
                        out.write(buf, 0, n)
                        onProgress(total)
                    }
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: java.io.IOException) {
            return@withContext UpdateResult.Failed("Download failed: ${e.message}")
        }
        val text = bytes.toString(Charsets.UTF_8)
        val pack = runCatching { CanonicalCodeSet.json.decodeFromString(CodePack.serializer(), text) }.getOrElse {
            return@withContext UpdateResult.Failed("Not a Universal IR code pack: ${it.message?.take(100)}")
        }
        var imported = 0; var dup = 0; var rejected = 0
        for (set in pack.codeSets.take(500)) {
            val single = CanonicalCodeSet.json.encodeToString(CanonicalCodeSet.serializer(), set)
            val report = CanonicalImporter.import(single)
            rejected += report.rejected.size
            val valid = report.codeSet ?: continue
            when (codes.saveCodeSet(valid.copy(id = "update-" + valid.id.take(40)), "IMPORTED", valid.models.firstOrNull() ?: "Update")) {
                is ImportOutcome.Imported -> imported++
                is ImportOutcome.Duplicate -> dup++
                is ImportOutcome.Invalid -> rejected++
            }
        }
        UpdateResult.Done(imported, dup, rejected)
    }

    companion object {
        const val MAX_BYTES = 5_000_000L
    }
}
