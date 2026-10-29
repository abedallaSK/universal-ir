package com.universalir

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.universalir.core.ir.AndroidIrTransmitter
import com.universalir.core.ir.HardwareStatus
import com.universalir.core.ir.TransmitResult
import com.universalir.data.local.AppDatabase
import com.universalir.data.local.BrandAliasEntity
import com.universalir.data.local.BrandEntity
import com.universalir.data.local.CodeSetEntity
import com.universalir.data.local.DeviceModelEntity
import com.universalir.data.local.RemoteProfileEntity
import com.universalir.data.local.RoomEntity
import com.universalir.data.local.SavedDeviceEntity
import com.universalir.data.local.VerificationEntity
import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.IrTransmission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseAndTransmitterTest {
    private lateinit var db: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.catalog().replaceBundled(
            brands = listOf(BrandEntity("brand-tadiran", "Tadiran", "tadiran", "AIR_CONDITIONER", popular = true)),
            aliases = listOf(BrandAliasEntity("brand-tadiran", "תדיראן", "תדיראן", "he")),
            models = listOf(DeviceModelEntity("m1", "brand-tadiran", "TAC490", "tac490", "AIR_CONDITIONER")),
            codeSets = listOf(CodeSetEntity("cs1", "brand-tadiran", "AIR_CONDITIONER", "tadiran64", "PROTOCOL", null, "protocol", 38_000, origin = "BUNDLED", title = "Tadiran")),
            profiles = listOf(RemoteProfileEntity("p1", "m1", "TAC490", "tac490", "cs1", "tadiran64", "IRTadiran", "https://x", "MIT", "WAVEFORM_VALIDATED", null, 0)),
        )
    }

    @Test
    fun searchFindsBrandByHebrewAliasAndModelNumber() = runTest {
        seed()
        assertEquals("Tadiran", db.catalog().searchBrands("תדיראן", "AIR_CONDITIONER").first().single().name)
        assertEquals("m1", db.catalog().search("tac490", null).first().single().modelId)
        assertTrue(db.catalog().search("tac490", "TELEVISION").first().isEmpty())
    }

    @Test
    fun savedDevicesRoomsAndVerificationPersist() = runTest {
        seed()
        val room = db.rooms().insert(RoomEntity(name = "Bedroom", icon = "bedroom", color = 0xFF2F6BFF, sortOrder = 0))
        val id = db.devices().insert(SavedDeviceEntity(0, "AC", "AIR_CONDITIONER", "Tadiran", "m1", "cs1", room, "{}", false, 0, 1, null))
        assertEquals(room, db.devices().get(id)!!.roomId)
        db.rooms().delete(room)
        assertNull(db.devices().get(id)!!.roomId) // device kept, room cleared
        db.verifications().insert(VerificationEntity(codeSetId = "cs1", deviceModelId = "m1", testedPhone = "test", testedFunction = "power", result = "WORKED", timestamp = 1))
        db.catalog().promoteFromVerifications()
        val row = db.catalog().codeSetRows("m1", null, null, null).first().single()
        assertEquals("HARDWARE_TESTED", row.verificationStatus)
        // Re-seeding must not erase user evidence.
        seed()
        assertEquals("HARDWARE_TESTED", db.catalog().codeSetRows("m1", null, null, null).first().single().verificationStatus)
    }

    @Test
    fun schemaVersionOneOpensFromExportedJson() {
        migrations.createDatabase("migration-test", AppDatabase.VERSION).close()
    }

    @Test
    fun transmitterNeverClaimsSuccessWithoutEmitter() = runTest {
        val t = AndroidIrTransmitter(context)
        val status = t.checkHardwareSupport()
        val result = t.transmit(IrTransmission.single(IrPattern(38_000, intArrayOf(10_000))))
        if (status is HardwareStatus.Available) assertTrue(result is TransmitResult.Sent)
        else assertTrue(result is TransmitResult.HardwareUnavailable)
    }

    @Test
    fun transmitterRejectsOverlongPattern() = runTest {
        val t = AndroidIrTransmitter(context)
        val result = t.transmit(IrTransmission.single(IrPattern(38_000, IntArray(9) { 250_000 })))
        assertTrue(result is TransmitResult.InvalidPattern || result is TransmitResult.HardwareUnavailable)
    }
}
