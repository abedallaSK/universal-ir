package com.universalir.data.repository

import android.content.Context
import android.os.Build
import android.util.LruCache
import com.universalir.data.catalog.CatalogSeeder
import com.universalir.data.local.AppDatabase
import com.universalir.data.local.BrandEntity
import com.universalir.data.local.CatalogCounts
import com.universalir.data.local.CodeSetEntity
import com.universalir.data.local.CodeSetRow
import com.universalir.data.local.DeviceModelEntity
import com.universalir.data.local.ModelSearchRow
import com.universalir.data.local.RemoteProfileEntity
import com.universalir.data.local.VerificationEntity
import com.universalir.domain.model.CodeSetSummary
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.domain.model.TextNormalizer
import com.universalir.domain.model.categoryOf
import com.universalir.domain.model.verificationOf
import com.universalir.ir.format.CanonicalCodeSet
import com.universalir.ir.format.CanonicalImporter
import com.universalir.ir.format.CodeSource
import com.universalir.ir.format.FlipperIrImporter
import com.universalir.ir.format.FormatDetector
import com.universalir.ir.format.ImportContext
import com.universalir.ir.format.ImportReport
import com.universalir.ir.format.IrdbCsvImporter
import com.universalir.ir.format.SmartIrClimateImporter
import com.universalir.ir.format.SmartIrDeviceImporter
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.ProtocolRegistry
import com.universalir.ir.protocol.VerificationLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LoadResult {
    data class Ok(val set: LoadedCodeSet) : LoadResult
    data class Error(val reason: String) : LoadResult
}

sealed interface ImportOutcome {
    data class Imported(val codeSetId: String, val modelId: String, val accepted: Int, val rejected: List<String>) : ImportOutcome
    data class Duplicate(val existingTitle: String) : ImportOutcome
    data class Invalid(val reason: String, val rejected: List<String> = emptyList()) : ImportOutcome
}

data class ImportRequest(
    val text: String,
    val fileName: String,
    val brand: String,
    val model: String,
    val category: DeviceCategory,
    val license: String,
    val sourceUrl: String = "user-file://$fileName",
)

@Singleton
class CodeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val cache = LruCache<String, LoadedCodeSet>(12)

    fun searchBrands(query: String, category: DeviceCategory?): Flow<List<BrandEntity>> =
        db.catalog().searchBrands(TextNormalizer.normalize(query), category?.name)

    fun getBrands(category: DeviceCategory?): Flow<List<BrandEntity>> = searchBrands("", category)

    fun getModels(brandId: String, category: DeviceCategory?, query: String = "", limit: Int = 100, offset: Int = 0): Flow<List<ModelSearchRow>> =
        db.catalog().models(brandId, category?.name, TextNormalizer.normalize(query), limit, offset)

    fun searchDevices(query: String, category: DeviceCategory? = null, limit: Int = 100, offset: Int = 0): Flow<List<ModelSearchRow>> =
        db.catalog().search(TextNormalizer.normalize(query), category?.name, limit, offset)

    fun getCodeSets(modelId: String?, brandId: String? = null, category: DeviceCategory? = null, protocolId: String? = null): Flow<List<CodeSetSummary>> =
        db.catalog().codeSetRows(modelId, brandId, category?.name, protocolId).map { rows -> rows.map(::toSummary) }

    fun protocols(): Flow<List<String>> = db.catalog().protocols()

    fun counts(): Flow<CatalogCounts> = db.catalog().counts()

    suspend fun brand(id: String) = db.catalog().brand(id)

    suspend fun codeSetEntity(id: String) = db.catalog().codeSet(id)

    /** The bundled full remote for the same brand and category as [codeSetId], if it isn't already that one. */
    suspend fun fullRemoteFor(codeSetId: String): CodeSetEntity? {
        val current = db.catalog().codeSet(codeSetId) ?: return null
        if (current.id.startsWith("fullremote-")) return null
        return db.catalog().fullRemoteFor(current.brandId, current.category)
    }

    private fun toSummary(r: CodeSetRow) = CodeSetSummary(
        codeSetId = r.codeSetId, title = r.title, brandName = r.brandName, modelNumber = r.modelNumber, modelId = r.modelId,
        category = categoryOf(r.category), protocolId = r.protocolId, payloadKind = r.payloadKind, origin = r.origin,
        commandCount = r.commandCount, remoteModel = r.remoteModelNumber, sourceRepository = r.sourceRepository,
        sourceUrl = r.sourceUrl, license = r.licenseIdentifier, verification = verificationOf(r.verificationStatus), evidence = r.evidence,
    )

    /** Loads (and caches) a code set's commands. Bundled payloads are read from assets on demand. */
    suspend fun load(codeSetId: String): LoadResult = withContext(Dispatchers.IO) {
        cache.get(codeSetId)?.let { return@withContext LoadResult.Ok(it) }
        val e = db.catalog().codeSet(codeSetId) ?: return@withContext LoadResult.Error("Code set $codeSetId not found")
        val result = when (e.payloadKind) {
            "PROTOCOL" -> ProtocolRegistry.ac(e.protocolId)?.let { LoadResult.Ok(LoadedCodeSet.AcProtocolSet(e.id, e.title, it)) }
                ?: LoadResult.Error("Protocol ${e.protocolId} is not implemented in this version")
            "ASSET" -> loadAsset(e)
            "INLINE" -> loadInline(e)
            else -> LoadResult.Error("Unknown payload kind ${e.payloadKind}")
        }
        if (result is LoadResult.Ok) cache.put(codeSetId, result.set)
        result
    }

    private fun loadAsset(e: CodeSetEntity): LoadResult {
        val text = try {
            context.assets.open(e.rawPayload ?: return LoadResult.Error("Missing asset path")).bufferedReader().use { it.readText() }
        } catch (ex: Exception) {
            return LoadResult.Error("Bundled file unavailable: ${ex.message}")
        }
        val report = when (e.payloadFormat) {
            "canonical" -> CanonicalImporter.import(text)
            "flipper_ir" -> FlipperIrImporter.import(text, ImportContext(e.id, e.brandId, categoryOf(e.category), source = CodeSource("Flipper-IRDB", "", license = "CC0-1.0")))
            else -> {
                val ctx = ImportContext(e.id, e.brandId, categoryOf(e.category), source = CodeSource("SmartIR", "", license = "MIT"))
                if (e.payloadFormat == "smartir_climate") SmartIrClimateImporter.import(text, ctx) else SmartIrDeviceImporter.import(text, ctx)
            }
        }
        return toLoaded(e, report)
    }

    private fun loadInline(e: CodeSetEntity): LoadResult = toLoaded(e, CanonicalImporter.import(e.rawPayload ?: ""))

    private fun toLoaded(e: CodeSetEntity, report: ImportReport): LoadResult {
        val set = report.codeSet ?: return LoadResult.Error(report.fatal ?: "No usable commands")
        return LoadResult.Ok(
            if (set.protocolId == SmartIrClimateImporter.PROTOCOL_ID) LoadedCodeSet.AcTableSet(e.id, e.title, set)
            else LoadedCodeSet.CommandSet(e.id, e.title, set),
        )
    }

    fun clearCache() = cache.evictAll()

    suspend fun recordVerificationResult(codeSetId: String, modelId: String?, function: String, result: String, notes: String? = null) {
        db.verifications().insert(
            VerificationEntity(
                codeSetId = codeSetId, deviceModelId = modelId,
                testedPhone = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
                testedFunction = function, result = result, timestamp = System.currentTimeMillis(), notes = notes,
            ),
        )
        if (result == "WORKED") db.catalog().promoteFromVerifications()
    }

    fun verifications(codeSetId: String) = db.verifications().forCodeSet(codeSetId)
    fun recentVerifications() = db.verifications().recent()

    /** Imports a user-supplied file after format detection, validation and duplicate detection. */
    suspend fun importFile(req: ImportRequest): ImportOutcome = withContext(Dispatchers.Default) {
        val id = "import-" + UUID.randomUUID().toString().take(8)
        val src = CodeSource(repository = "User file: ${req.fileName}", url = req.sourceUrl, path = req.fileName, license = req.license)
        val ctx = ImportContext(id, req.brand, req.category, listOf(req.model), src)
        val report = when (FormatDetector.detect(req.text)) {
            FormatDetector.Kind.FLIPPER -> FlipperIrImporter.import(req.text, ctx)
            FormatDetector.Kind.IRDB_CSV -> IrdbCsvImporter.import(req.text, ctx)
            FormatDetector.Kind.SMARTIR_JSON ->
                if (req.category == DeviceCategory.AIR_CONDITIONER) SmartIrClimateImporter.import(req.text, ctx) else SmartIrDeviceImporter.import(req.text, ctx)
            FormatDetector.Kind.CANONICAL_JSON -> CanonicalImporter.import(req.text).let { r -> r.copy(codeSet = r.codeSet?.let { c -> c.copy(id = id) }) }
            FormatDetector.Kind.UNKNOWN -> return@withContext ImportOutcome.Invalid("Unrecognised file format. Supported: Flipper .ir, IRDB CSV, SmartIR JSON, Universal IR export.")
        }
        val rejected = report.rejected.map { "${it.item}: ${it.reason}" }
        val set = report.codeSet ?: return@withContext ImportOutcome.Invalid(report.fatal ?: "No valid commands", rejected)
        saveCodeSet(set, origin = "IMPORTED", modelName = req.model, rejected = rejected)
    }

    /** Persists a canonical set (imported or user-built) with brand/model/profile rows. */
    suspend fun saveCodeSet(set: CanonicalCodeSet, origin: String, modelName: String, rejected: List<String> = emptyList()): ImportOutcome {
        val hash = contentHash(set)
        // User-built remotes may legitimately repeat commands (e.g. re-saving a layout).
        if (origin != "USER") db.catalog().codeSetByHash(hash)?.let { return ImportOutcome.Duplicate(it.title) }
        val normalizedBrand = TextNormalizer.normalize(set.brand).ifEmpty { "unknown" }
        val existing = db.catalog().brandByName(normalizedBrand)
        val brand = existing ?: BrandEntity("brand-$normalizedBrand", set.brand.ifBlank { "Unknown" }, normalizedBrand, set.category.name)
        val modelId = "m-${set.id}"
        val json = CanonicalCodeSet.json.encodeToString(CanonicalCodeSet.serializer(), set)
        val codeSet = CodeSetEntity(
            id = set.id, brandId = brand.id, category = set.category.name, protocolId = set.protocolId, payloadKind = "INLINE",
            rawPayload = json, payloadFormat = "canonical", carrierFrequencyHz = set.commands.firstOrNull()?.carrierHz,
            origin = origin, contentHash = hash, title = "${set.brand} ${modelName}".trim(), commandCount = set.commands.size,
        )
        val model = DeviceModelEntity(modelId, brand.id, modelName.ifBlank { "Custom" }, TextNormalizer.normalize(modelName), set.category.name, protocolId = set.protocolId)
        val profile = RemoteProfileEntity(
            id = "p-${set.id}", deviceModelId = modelId, remoteModelNumber = set.remoteModel,
            normalizedRemoteModel = set.remoteModel?.let(TextNormalizer::normalize), codeSetId = set.id, protocolId = set.protocolId,
            sourceRepository = set.source.repository, sourceUrl = set.source.url, licenseIdentifier = set.source.license,
            verificationStatus = VerificationLevel.SOURCE_DISCOVERED.name,
            evidence = if (origin == "USER") "Created in this app." else "Imported file; codes validated for format and timing limits only.",
            lastUpdated = System.currentTimeMillis(),
        )
        // Keep the brand's category list complete so the brand shows up under the new category.
        val brandRow = if (existing != null && set.category.name !in existing.categories.split(',')) {
            existing.copy(categories = existing.categories + "," + set.category.name)
        } else brand
        db.catalog().insertImported(brandRow, model, codeSet, profile)
        if (existing != null && brandRow !== existing) db.catalog().upsertBrands(listOf(brandRow))
        return ImportOutcome.Imported(set.id, modelId, set.commands.size, rejected)
    }

    suspend fun exportUserCodeSets(): List<CanonicalCodeSet> =
        db.catalog().userCodeSets().mapNotNull { e ->
            CanonicalImporter.import(e.rawPayload ?: return@mapNotNull null).codeSet
        }

    suspend fun removeUnverifiedImported(): Int {
        val n = db.catalog().deleteUnverifiedImported()
        db.catalog().deleteOrphanModels()
        clearCache()
        return n
    }

    fun bundledCatalogInfo(seeder: CatalogSeeder) = runCatching { seeder.readCatalog() }.getOrNull()

    private fun contentHash(set: CanonicalCodeSet): String {
        val md = MessageDigest.getInstance("SHA-256")
        set.commands.sortedBy { it.name }.forEach { c ->
            md.update(c.name.toByteArray())
            md.update((c.carrierHz ?: 0).toString().toByteArray())
            c.timings?.forEach { t -> md.update(t.toString().toByteArray()); md.update(','.code.toByte()) }
            md.update("${c.protocol}:${c.address}:${c.command}".toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
