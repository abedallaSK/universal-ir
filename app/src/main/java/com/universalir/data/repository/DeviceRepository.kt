package com.universalir.data.repository

import android.content.Context
import com.universalir.data.local.AppDatabase
import com.universalir.widget.Widgets
import dagger.hilt.android.qualifiers.ApplicationContext
import com.universalir.data.local.DiagnosticRecordEntity
import com.universalir.data.local.FavoriteCommandEntity
import com.universalir.data.local.RoomEntity
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.domain.model.DeviceSettings
import com.universalir.ir.protocol.DeviceCategory
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(
    private val db: AppDatabase,
    @ApplicationContext private val context: Context,
) {
    /** Home-screen widgets show device names, looks, favorites and AC state, so redraw them after edits. */
    private fun <T> T.refreshWidgets(): T = also { Widgets.refreshAll(context) }


    fun listDevices(): Flow<List<SavedDeviceEntity>> = db.devices().all()
    fun recentDevices(): Flow<List<SavedDeviceEntity>> = db.devices().recent()
    fun observeDevice(id: Long): Flow<SavedDeviceEntity?> = db.devices().observe(id)
    suspend fun device(id: Long): SavedDeviceEntity? = db.devices().get(id)

    suspend fun addDevice(
        name: String,
        category: DeviceCategory,
        brandName: String,
        modelId: String?,
        codeSetId: String,
        roomId: Long? = null,
        settings: DeviceSettings = DeviceSettings(),
    ): Long = db.devices().insert(
        SavedDeviceEntity(
            displayName = name.trim().ifEmpty { brandName }, category = category.name, brandName = brandName,
            modelId = modelId, codeSetId = codeSetId, roomId = roomId, customSettings = settings.encode(),
            favorite = false, sortOrder = db.devices().nextSortOrder(), createdAt = System.currentTimeMillis(), lastUsedAt = null,
        ),
    )

    suspend fun updateDevice(device: SavedDeviceEntity) = db.devices().update(device)

    suspend fun rename(id: Long, name: String) {
        val d = db.devices().get(id) ?: return
        if (name.isNotBlank()) db.devices().update(d.copy(displayName = name.trim()))
        Widgets.refreshAll(context)
    }

    suspend fun deleteDevice(id: Long) = (db.devices().delete(id)).refreshWidgets()

    suspend fun duplicateDevice(id: Long, copySuffix: String): Long? {
        val d = db.devices().get(id) ?: return null
        return db.devices().insert(
            d.copy(id = 0, displayName = "${d.displayName} $copySuffix", favorite = false,
                sortOrder = db.devices().nextSortOrder(), createdAt = System.currentTimeMillis(), lastUsedAt = null),
        ).refreshWidgets()
    }

    suspend fun assignRoom(id: Long, roomId: Long?) = (db.devices().assignRoom(id, roomId)).refreshWidgets()
    suspend fun setFavorite(id: Long, favorite: Boolean) = (db.devices().setFavorite(id, favorite)).refreshWidgets()
    suspend fun reorder(idsInOrder: List<Long>) = (db.devices().reorder(idsInOrder)).refreshWidgets()
    suspend fun markUsed(id: Long) = db.devices().touch(id, System.currentTimeMillis())
    suspend fun saveSettings(id: Long, settings: DeviceSettings) = (db.devices().setSettings(id, settings.encode())).refreshWidgets()

    /** Read-modify-write of the per-device JSON; a damaged profile is left for the remote screen to reset. */
    suspend fun updateSettings(id: Long, change: (DeviceSettings) -> DeviceSettings) {
        val d = db.devices().get(id) ?: return
        val current = DeviceSettings.decode(d.customSettings) ?: return
        saveSettings(id, change(current))
    }

    // Rooms
    fun rooms(): Flow<List<RoomEntity>> = db.rooms().all()
    suspend fun createRoom(name: String, icon: String, color: Long): Long =
        db.rooms().insert(RoomEntity(name = name.trim(), icon = icon, color = color, sortOrder = db.rooms().nextSortOrder()))
    suspend fun updateRoom(room: RoomEntity) = db.rooms().update(room)
    suspend fun deleteRoom(id: Long) = db.rooms().delete(id)

    // Favorites
    fun favoriteCommands(): Flow<List<FavoriteCommandEntity>> = db.favorites().all()
    suspend fun addFavoriteCommand(deviceId: Long, label: String, commandName: String?, acStateJson: String?) =
        db.favorites().insert(FavoriteCommandEntity(deviceId = deviceId, label = label, commandName = commandName, acStateJson = acStateJson, sortOrder = db.favorites().nextSortOrder())).refreshWidgets()
    suspend fun deleteFavoriteCommand(id: Long) = (db.favorites().delete(id)).refreshWidgets()

    // Diagnostics
    fun diagnostics(): Flow<List<DiagnosticRecordEntity>> = db.diagnostics().recent()
    suspend fun addDiagnostic(record: DiagnosticRecordEntity) = db.diagnostics().insert(record)
    suspend fun clearDiagnostics() = db.diagnostics().clear()
}
