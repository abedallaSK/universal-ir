package com.universalir.data.catalog

import android.content.Context
import com.universalir.data.local.AppDatabase
import com.universalir.data.local.BrandAliasEntity
import com.universalir.data.local.BrandEntity
import com.universalir.data.local.CodeSetEntity
import com.universalir.data.local.DeviceModelEntity
import com.universalir.data.local.RemoteProfileEntity
import com.universalir.data.repository.SettingsRepository
import com.universalir.domain.model.TextNormalizer
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.ProtocolRegistry
import com.universalir.ir.protocol.VerificationLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BundledCatalog(val version: Int, val source: String, val license: String, val entries: List<BundledEntry>)

@Serializable
data class BundledEntry(
    val id: String,
    val category: String,
    val brand: String,
    val models: List<String>,
    val asset: String,
    val format: String,
    val fingerprintProtocol: String? = null,
    val sourceUrl: String,
    /** Per-entry overrides; the catalogue-level source/license apply when absent. */
    val title: String? = null,
    val repository: String? = null,
    val license: String? = null,
    val evidence: String? = null,
    /** Extra brands that also list this code set (e.g. "Unknown / generic Chinese box"). */
    val extraBrands: List<String> = emptyList(),
)

sealed interface SeedResult {
    data object UpToDate : SeedResult
    data class Seeded(val codeSets: Int) : SeedResult
    data class Failed(val reason: String) : SeedResult
}

/**
 * Loads the bundled catalogue index into Room. Only metadata is stored; code payloads stay in
 * assets and are parsed lazily when a remote is opened.
 */
@Singleton
class CatalogSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun seedIfNeeded(): SeedResult = withContext(Dispatchers.IO) {
        try {
            val catalog = readCatalog()
            val version = catalog.version * 1000 + SEED_LOGIC_VERSION
            if (settings.current().catalogSeedVersion == version) return@withContext SeedResult.UpToDate
            val result = seed(catalog)
            settings.setCatalogSeedVersion(version)
            result
        } catch (e: Exception) {
            SeedResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun readCatalog(): BundledCatalog =
        context.assets.open("catalog/catalog.json").bufferedReader().use { json.decodeFromString(BundledCatalog.serializer(), it.readText()) }

    private suspend fun seed(catalog: BundledCatalog): SeedResult {
        val now = System.currentTimeMillis()
        // Keyed by normalised name so "LG" and "Lg" become one brand (the unique index requires it).
        val displayNames = LinkedHashMap<String, String>()
        val brandCats = LinkedHashMap<String, MutableSet<String>>()
        fun brandOf(name: String, category: String) {
            val key = TextNormalizer.normalize(name).ifEmpty { "unknown" }
            displayNames.putIfAbsent(key, name.trim())
            brandCats.getOrPut(key) { linkedSetOf() } += category
        }
        catalog.entries.forEach { e -> brandOf(e.brand, e.category); e.extraBrands.forEach { brandOf(it, e.category) } }
        KnownBrands.protocolLinks.forEach { brandOf(it.brand, DeviceCategory.AIR_CONDITIONER.name) }

        val brands = brandCats.map { (key, cats) ->
            val name = displayNames.getValue(key)
            BrandEntity("brand-$key", name, key, cats.joinToString(","), name in KnownBrands.popular)
        }
        val aliases = brands.flatMap { b ->
            KnownBrands.aliases[b.name].orEmpty().map { BrandAliasEntity(b.id, it.text, TextNormalizer.normalize(it.text), it.language) } +
                BrandAliasEntity(b.id, b.name, b.normalizedName, "en")
        }

        val models = mutableListOf<DeviceModelEntity>()
        val codeSets = mutableListOf<CodeSetEntity>()
        val profiles = mutableListOf<RemoteProfileEntity>()

        for (e in catalog.entries) {
            val protoName = e.fingerprintProtocol?.let { ProtocolRegistry.info(it)?.displayName }
            val protocolId = when (e.format) {
                "smartir_climate" -> "smartir_table"
                "canonical" -> e.fingerprintProtocol ?: "raw"
                else -> "raw"
            }
            codeSets += CodeSetEntity(
                id = e.id, brandId = brandId(e.brand), category = e.category,
                protocolId = protocolId,
                payloadKind = "ASSET", rawPayload = e.asset, payloadFormat = e.format, carrierFrequencyHz = 38_000,
                checksumMetadata = null, featureMetadata = e.fingerprintProtocol, origin = "BUNDLED",
                title = e.title ?: (titlePrefix(e) + (protoName?.let { " · $it" } ?: "")),
            )
            val evidence = e.evidence ?: when (e.format) {
                "flipper_ir" -> "Community-captured remote codes (Flipper Zero). Not tested by this app."
                else -> "Community-captured remote codes (Broadlink). Not tested by this app." +
                    (e.fingerprintProtocol?.let { " Timing structure matches $it." } ?: "")
            }
            val brandsForEntry = listOf(e.brand) + e.extraBrands
            brandsForEntry.forEachIndexed { b, brandName ->
                e.models.forEachIndexed { i, model ->
                    // Keep the original ids for the entry's own brand so saved devices stay linked.
                    val suffix = if (b == 0) "$i" else "x$b-$i"
                    val modelId = "m-${e.id}-$suffix"
                    val label = if (b == 0) model else "${e.brand} $model"
                    models += DeviceModelEntity(modelId, brandId(brandName), label, TextNormalizer.normalize(label), e.category, protocolId = e.fingerprintProtocol)
                    profiles += RemoteProfileEntity(
                        id = "p-${e.id}-$suffix", deviceModelId = modelId, remoteModelNumber = null, normalizedRemoteModel = null,
                        codeSetId = e.id, protocolId = e.fingerprintProtocol ?: "raw", sourceRepository = e.repository ?: catalog.source,
                        sourceUrl = e.sourceUrl, licenseIdentifier = e.license ?: catalog.license,
                        verificationStatus = VerificationLevel.SOURCE_DISCOVERED.name,
                        evidence = evidence,
                        lastUpdated = now,
                    )
                }
            }
        }

        for (p in ProtocolRegistry.acProtocols) {
            codeSets += CodeSetEntity(
                id = protocolCodeSetId(p.info.id), brandId = "", category = DeviceCategory.AIR_CONDITIONER.name,
                protocolId = p.info.id, payloadKind = "PROTOCOL", rawPayload = null, payloadFormat = "protocol",
                carrierFrequencyHz = p.info.carrierHz, checksumMetadata = p.info.frameDescription,
                featureMetadata = p.capabilities.features.joinToString(","), origin = "BUNDLED",
                title = p.info.displayName,
            )
        }
        for (link in KnownBrands.protocolLinks) {
            val modelId = "proto-${brandId(link.brand)}-${link.protocolId}"
            models += DeviceModelEntity(modelId, brandId(link.brand), link.modelLabel, TextNormalizer.normalize(link.modelLabel),
                DeviceCategory.AIR_CONDITIONER.name, protocolId = link.protocolId)
            profiles += RemoteProfileEntity(
                id = "p-$modelId", deviceModelId = modelId, remoteModelNumber = link.remoteModels,
                normalizedRemoteModel = link.remoteModels?.let(TextNormalizer::normalize), codeSetId = protocolCodeSetId(link.protocolId),
                protocolId = link.protocolId, sourceRepository = link.sourceRepository, sourceUrl = link.sourceUrl,
                licenseIdentifier = link.license, verificationStatus = link.level.name, evidence = link.evidence, lastUpdated = now,
            )
        }

        db.catalog().replaceBundled(brands, aliases, models, codeSets, profiles)
        return SeedResult.Seeded(codeSets.size)
    }

    companion object {
        /** Bump when seeding logic changes so existing installs re-seed. */
        const val SEED_LOGIC_VERSION = 2

        private fun titlePrefix(e: BundledEntry): String {
            val n = e.id.substringAfterLast('-')
            return when {
                e.format == "flipper_ir" -> "Flipper-IRDB · ${e.brand}"
                e.id.startsWith("smartir-up-") -> "SmartIR upstream #$n"
                else -> "SmartIR #$n"
            }
        }

        fun brandId(name: String) = "brand-" + TextNormalizer.normalize(name).ifEmpty { "unknown" }
        fun protocolCodeSetId(protocolId: String) = "protocol:$protocolId"
    }
}
