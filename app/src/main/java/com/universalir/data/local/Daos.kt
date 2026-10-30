package com.universalir.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Upsert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ModelSearchRow(
    val modelId: String,
    val modelNumber: String,
    val deviceType: String,
    val brandId: String,
    val brandName: String,
)

data class CodeSetRow(
    val profileId: String,
    val codeSetId: String,
    val title: String,
    val protocolId: String,
    val payloadKind: String,
    val origin: String,
    val commandCount: Int,
    val remoteModelNumber: String?,
    val sourceRepository: String,
    val sourceUrl: String,
    val licenseIdentifier: String,
    val verificationStatus: String,
    val evidence: String?,
    val modelNumber: String,
    val modelId: String,
    val brandName: String,
    val category: String,
)

data class CatalogCounts(val brands: Int, val models: Int, val codeSets: Int, val imported: Int, val user: Int)

@Dao
interface CatalogDao {
    @Upsert suspend fun upsertBrands(items: List<BrandEntity>)
    @Upsert suspend fun upsertAliases(items: List<BrandAliasEntity>)
    @Upsert suspend fun upsertModels(items: List<DeviceModelEntity>)
    @Upsert suspend fun upsertCodeSets(items: List<CodeSetEntity>)
    @Upsert suspend fun upsertProfiles(items: List<RemoteProfileEntity>)

    @Query("SELECT * FROM brands WHERE id = :id") suspend fun brand(id: String): BrandEntity?
    @Query("SELECT * FROM brands WHERE normalizedName = :normalized") suspend fun brandByName(normalized: String): BrandEntity?

    @Query(
        """
        SELECT DISTINCT b.* FROM brands b
        LEFT JOIN brand_aliases a ON a.brandId = b.id
        WHERE (:category IS NULL OR (',' || b.categories || ',') LIKE '%,' || :category || ',%')
          AND (:q = '' OR b.normalizedName LIKE '%' || :q || '%' OR a.normalizedAlias LIKE '%' || :q || '%')
        ORDER BY b.popular DESC, b.name COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun searchBrands(q: String, category: String?, limit: Int = 200): Flow<List<BrandEntity>>

    @Query("SELECT * FROM brand_aliases WHERE brandId = :brandId") suspend fun aliases(brandId: String): List<BrandAliasEntity>

    @Query(
        """
        SELECT m.id AS modelId, m.modelNumber, m.deviceType, b.id AS brandId, b.name AS brandName
        FROM device_models m JOIN brands b ON b.id = m.brandId
        WHERE m.brandId = :brandId AND (:category IS NULL OR m.deviceType = :category)
          AND (:q = '' OR m.normalizedModel LIKE '%' || :q || '%' OR m.id IN (
                SELECT p.deviceModelId FROM remote_profiles p WHERE p.normalizedRemoteModel LIKE '%' || :q || '%'))
        ORDER BY m.modelNumber COLLATE NOCASE LIMIT :limit OFFSET :offset
        """,
    )
    fun models(brandId: String, category: String?, q: String, limit: Int = 100, offset: Int = 0): Flow<List<ModelSearchRow>>

    /** Global search across brands, aliases, device models and remote model numbers. */
    @Query(
        """
        SELECT DISTINCT m.id AS modelId, m.modelNumber, m.deviceType, b.id AS brandId, b.name AS brandName
        FROM device_models m JOIN brands b ON b.id = m.brandId
        LEFT JOIN brand_aliases a ON a.brandId = b.id
        LEFT JOIN remote_profiles p ON p.deviceModelId = m.id
        WHERE (:category IS NULL OR m.deviceType = :category)
          AND (m.normalizedModel LIKE '%' || :q || '%' OR b.normalizedName LIKE '%' || :q || '%'
               OR a.normalizedAlias LIKE '%' || :q || '%' OR p.normalizedRemoteModel LIKE '%' || :q || '%')
        ORDER BY b.name COLLATE NOCASE, m.modelNumber COLLATE NOCASE
        LIMIT :limit OFFSET :offset
        """,
    )
    fun search(q: String, category: String?, limit: Int = 100, offset: Int = 0): Flow<List<ModelSearchRow>>

    @Query(
        """
        SELECT p.id AS profileId, c.id AS codeSetId, c.title, c.protocolId, c.payloadKind, c.origin, c.commandCount,
               p.remoteModelNumber, p.sourceRepository, p.sourceUrl, p.licenseIdentifier, p.verificationStatus, p.evidence,
               m.modelNumber, m.id AS modelId, b.name AS brandName, m.deviceType AS category
        FROM remote_profiles p
        JOIN code_sets c ON c.id = p.codeSetId
        JOIN device_models m ON m.id = p.deviceModelId
        JOIN brands b ON b.id = m.brandId
        WHERE (:modelId IS NULL OR p.deviceModelId = :modelId)
          AND (:brandId IS NULL OR m.brandId = :brandId)
          AND (:category IS NULL OR m.deviceType = :category)
          AND (:protocolId IS NULL OR c.protocolId = :protocolId)
        ORDER BY CASE p.verificationStatus
            WHEN 'FEATURE_TESTED' THEN 0 WHEN 'HARDWARE_TESTED' THEN 1 WHEN 'WAVEFORM_VALIDATED' THEN 2
            WHEN 'ENCODER_IMPLEMENTED' THEN 3 ELSE 4 END, c.title
        LIMIT :limit
        """,
    )
    fun codeSetRows(modelId: String?, brandId: String?, category: String?, protocolId: String?, limit: Int = 200): Flow<List<CodeSetRow>>

    @Query("SELECT * FROM code_sets WHERE id = :id") suspend fun codeSet(id: String): CodeSetEntity?
    @Query("SELECT * FROM code_sets WHERE id LIKE 'fullremote-%' AND brandId = :brandId AND category = :category LIMIT 1")
    suspend fun fullRemoteFor(brandId: String, category: String): CodeSetEntity?
    @Query("SELECT * FROM code_sets WHERE contentHash = :hash LIMIT 1") suspend fun codeSetByHash(hash: String): CodeSetEntity?
    @Query("SELECT DISTINCT protocolId FROM code_sets ORDER BY protocolId") fun protocols(): Flow<List<String>>

    @Query(
        """SELECT (SELECT COUNT(*) FROM brands) AS brands, (SELECT COUNT(*) FROM device_models) AS models,
           (SELECT COUNT(*) FROM code_sets) AS codeSets,
           (SELECT COUNT(*) FROM code_sets WHERE origin = 'IMPORTED') AS imported,
           (SELECT COUNT(*) FROM code_sets WHERE origin = 'USER') AS user""",
    )
    fun counts(): Flow<CatalogCounts>

    @Query("SELECT * FROM code_sets WHERE origin IN ('IMPORTED','USER')") suspend fun userCodeSets(): List<CodeSetEntity>

    /** Imported code sets nobody verified and no saved device uses. */
    @Query(
        """DELETE FROM code_sets WHERE origin = 'IMPORTED'
           AND id NOT IN (SELECT codeSetId FROM verifications WHERE result = 'WORKED')
           AND id NOT IN (SELECT codeSetId FROM saved_devices)""",
    )
    suspend fun deleteUnverifiedImported(): Int

    @Query("DELETE FROM device_models WHERE id NOT IN (SELECT deviceModelId FROM remote_profiles)") suspend fun deleteOrphanModels()

    @Query("DELETE FROM code_sets WHERE origin = 'BUNDLED' AND id NOT IN (SELECT codeSetId FROM saved_devices)")
    suspend fun deleteUnusedBundled()

    /**
     * Level 3 comes only from a user confirming a code worked on a real device; the promotion is
     * derived from the verification history so re-seeding the bundled catalogue cannot erase it.
     */
    @Query(
        """UPDATE remote_profiles SET verificationStatus = 'HARDWARE_TESTED'
           WHERE verificationStatus NOT IN ('HARDWARE_TESTED', 'FEATURE_TESTED')
             AND EXISTS (SELECT 1 FROM verifications v WHERE v.codeSetId = remote_profiles.codeSetId
                         AND v.deviceModelId = remote_profiles.deviceModelId AND v.result = 'WORKED')""",
    )
    suspend fun promoteFromVerifications()

    @Transaction
    suspend fun replaceBundled(
        brands: List<BrandEntity>, aliases: List<BrandAliasEntity>, models: List<DeviceModelEntity>,
        codeSets: List<CodeSetEntity>, profiles: List<RemoteProfileEntity>,
    ) {
        deleteUnusedBundled()
        upsertBrands(brands)
        upsertAliases(aliases)
        upsertModels(models)
        upsertCodeSets(codeSets)
        upsertProfiles(profiles)
        deleteOrphanModels()
        promoteFromVerifications()
    }

    @Transaction
    suspend fun insertImported(brand: BrandEntity, model: DeviceModelEntity, codeSet: CodeSetEntity, profile: RemoteProfileEntity) {
        if (brandByName(brand.normalizedName) == null) upsertBrands(listOf(brand))
        upsertModels(listOf(model))
        upsertCodeSets(listOf(codeSet))
        upsertProfiles(listOf(profile))
    }
}

@Dao
interface VerificationDao {
    @Insert suspend fun insert(v: VerificationEntity): Long
    @Query("SELECT * FROM verifications WHERE codeSetId = :codeSetId ORDER BY timestamp DESC") fun forCodeSet(codeSetId: String): Flow<List<VerificationEntity>>
    @Query("SELECT * FROM verifications ORDER BY timestamp DESC LIMIT 200") fun recent(): Flow<List<VerificationEntity>>
}

@Dao
interface DeviceDao {
    @Insert suspend fun insert(d: SavedDeviceEntity): Long
    @Update suspend fun update(d: SavedDeviceEntity)
    @Query("DELETE FROM saved_devices WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM saved_devices WHERE id = :id") suspend fun get(id: Long): SavedDeviceEntity?
    @Query("SELECT * FROM saved_devices WHERE id = :id") fun observe(id: Long): Flow<SavedDeviceEntity?>
    @Query("SELECT * FROM saved_devices ORDER BY sortOrder, createdAt") fun all(): Flow<List<SavedDeviceEntity>>
    @Query("SELECT * FROM saved_devices WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT :n") fun recent(n: Int = 6): Flow<List<SavedDeviceEntity>>
    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM saved_devices") suspend fun nextSortOrder(): Int
    @Query("UPDATE saved_devices SET lastUsedAt = :t WHERE id = :id") suspend fun touch(id: Long, t: Long)
    @Query("UPDATE saved_devices SET roomId = :roomId WHERE id = :id") suspend fun assignRoom(id: Long, roomId: Long?)
    @Query("UPDATE saved_devices SET favorite = :fav WHERE id = :id") suspend fun setFavorite(id: Long, fav: Boolean)
    @Query("UPDATE saved_devices SET sortOrder = :order WHERE id = :id") suspend fun setSortOrder(id: Long, order: Int)
    @Query("UPDATE saved_devices SET customSettings = :json WHERE id = :id") suspend fun setSettings(id: Long, json: String)

    @Transaction
    suspend fun reorder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { i, id -> setSortOrder(id, i) }
    }
}

@Dao
interface RoomDao {
    @Insert suspend fun insert(r: RoomEntity): Long
    @Update suspend fun update(r: RoomEntity)
    @Query("DELETE FROM rooms WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM rooms ORDER BY sortOrder, name") fun all(): Flow<List<RoomEntity>>
    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM rooms") suspend fun nextSortOrder(): Int
}

@Dao
interface FavoriteDao {
    @Insert suspend fun insert(f: FavoriteCommandEntity): Long
    @Query("DELETE FROM favorite_commands WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM favorite_commands ORDER BY sortOrder, id") fun all(): Flow<List<FavoriteCommandEntity>>
    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM favorite_commands") suspend fun nextSortOrder(): Int
}

@Dao
interface DiagnosticDao {
    @Insert suspend fun insert(r: DiagnosticRecordEntity): Long
    @Query("SELECT * FROM diagnostic_records ORDER BY timestamp DESC LIMIT 50") fun recent(): Flow<List<DiagnosticRecordEntity>>
    @Query("DELETE FROM diagnostic_records") suspend fun clear()
}
