package com.universalir.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        BrandEntity::class, BrandAliasEntity::class, DeviceModelEntity::class, CodeSetEntity::class,
        RemoteProfileEntity::class, VerificationEntity::class, RoomEntity::class, SavedDeviceEntity::class,
        FavoriteCommandEntity::class, DiagnosticRecordEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun verifications(): VerificationDao
    abstract fun devices(): DeviceDao
    abstract fun rooms(): RoomDao
    abstract fun favorites(): FavoriteDao
    abstract fun diagnostics(): DiagnosticDao

    companion object {
        const val VERSION = 1
        const val NAME = "universal_ir.db"

        /**
         * Explicit migrations only: user devices, rooms and verification history must survive
         * upgrades, so destructive fallback is never enabled. Add `Migration(n, n + 1)` here and
         * a schema JSON is exported to `app/schemas` for MigrationTestHelper.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
