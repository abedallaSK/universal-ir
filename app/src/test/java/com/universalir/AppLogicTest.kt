package com.universalir

import com.universalir.core.ir.HardwareStatus
import com.universalir.core.ir.IrTransmitter
import com.universalir.core.ir.TransmitResult
import com.universalir.domain.model.AcTable
import com.universalir.domain.model.DeviceSettings
import com.universalir.domain.model.LicensePolicy
import com.universalir.domain.model.LoadedCodeSet
import com.universalir.domain.model.TextNormalizer
import com.universalir.domain.usecase.RemoteController
import com.universalir.domain.usecase.SendOutcome
import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.IrTransmission
import com.universalir.ir.core.PatternIssue
import com.universalir.ir.core.PatternValidator
import com.universalir.ir.protocol.ac.AcMode
import com.universalir.ir.protocol.ac.AcState
import com.universalir.ir.protocol.ac.FanSpeed
import com.universalir.ir.protocol.ac.GreeProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {

    /** Fake hardware: records what would reach ConsumerIrManager. */
    private class FakeTransmitter(private val status: HardwareStatus) : IrTransmitter {
        val sent = mutableListOf<IrTransmission>()
        override fun checkHardwareSupport() = status
        override fun getSupportedCarrierRanges() = (status as? HardwareStatus.Available)?.carrierRanges ?: emptyList()
        override fun validatePattern(pattern: IrPattern): List<PatternIssue> = PatternValidator.validate(pattern)
        override suspend fun transmit(transmission: IrTransmission): TransmitResult {
            if (status !is HardwareStatus.Available) return TransmitResult.HardwareUnavailable(status)
            val issues = PatternValidator.validate(transmission, status.carrierRanges.ifEmpty { null })
            if (issues.isNotEmpty()) return TransmitResult.InvalidPattern(issues)
            sent += transmission
            return TransmitResult.Sent(transmission.messages.size, transmission.totalAirTimeUs, 0)
        }
    }

    private val gree = LoadedCodeSet.AcProtocolSet("protocol:gree64", "Gree", GreeProtocol())

    @Test
    fun noHardwareNeverReportsSuccess() = runTest {
        val remote = RemoteController(FakeTransmitter(HardwareStatus.NoFeature))
        val outcome = remote.sendAc(gree, AcState(true, AcMode.COOL, 24), null)
        assertTrue(outcome is SendOutcome.Transmit)
        assertTrue((outcome as SendOutcome.Transmit).result is TransmitResult.HardwareUnavailable)
    }

    @Test
    fun unsupportedCarrierIsRejectedBeforeTransmission() = runTest {
        val fake = FakeTransmitter(HardwareStatus.Available(listOf(30_000..33_000)))
        val outcome = RemoteController(fake).sendAc(gree, AcState(true, AcMode.COOL, 24), null)
        assertTrue((outcome as SendOutcome.Transmit).result is TransmitResult.InvalidPattern)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun validStateIsEncodedAndSent() = runTest {
        val fake = FakeTransmitter(HardwareStatus.Available(listOf(30_000..60_000)))
        val remote = RemoteController(fake)
        val outcome = remote.sendAc(gree, AcState(true, AcMode.COOL, 24, FanSpeed.LOW), null)
        assertTrue(outcome is SendOutcome.Success)
        assertEquals(1, fake.sent.size)
        assertEquals(38_000, fake.sent.single().messages.single().pattern.carrierHz)
        assertTrue(remote.lastEvent.value != null)
    }

    @Test
    fun invalidStateIsNotSent() = runTest {
        val fake = FakeTransmitter(HardwareStatus.Available(emptyList()))
        val outcome = RemoteController(fake).sendAc(gree, AcState(true, AcMode.COOL, 40), null)
        assertTrue(outcome is SendOutcome.EncodeError)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun acTableKeysRoundTrip() {
        val s = AcState(true, AcMode.FAN_ONLY, 22, FanSpeed.MEDIUM)
        assertEquals("fan_only/medium/22", AcTable.key(s))
        assertEquals(Triple(AcMode.FAN_ONLY, FanSpeed.MEDIUM, 22), AcTable.parse("fan_only/medium/22"))
        assertEquals("off", AcTable.key(s.copy(power = false)))
        val table = AcTable(setOf("off", "cool/low/16", "cool/low/17", "heat/high/30"))
        assertEquals(setOf(AcMode.COOL, AcMode.HEAT), table.modes)
        assertEquals(listOf(16, 17), table.temps(AcMode.COOL, FanSpeed.LOW))
    }

    @Test
    fun deviceSettingsPersistAndRejectCorruption() {
        val ds = DeviceSettings(hiddenButtons = setOf("mute"), acState = AcState(true, AcMode.HEAT, 26))
        assertEquals(ds, DeviceSettings.decode(ds.encode()))
        assertNull(DeviceSettings.decode("{not json"))
        assertEquals(DeviceSettings(), DeviceSettings.decode(""))
    }

    @Test
    fun brandAliasesNormaliseAcrossScripts() {
        assertEquals("תדיראן", TextNormalizer.normalize(" תדיראן "))
        assertEquals("格力", TextNormalizer.normalize("格力"))
        // Hamza is a combining mark after NFKD, so both common spellings of Electra match.
        assertEquals(TextNormalizer.normalize("الكترا"), TextNormalizer.normalize("إلكترا"))
        assertEquals("mszgl25vgd", TextNormalizer.normalize("MSZ-GL25VGD"))
        assertEquals("rg52dbge", TextNormalizer.normalize("RG52D/BGE"))
    }

    @Test
    fun exportPolicyFollowsLicense() {
        assertTrue(LicensePolicy.allowsExport("MIT"))
        assertTrue(LicensePolicy.allowsExport("CC0-1.0"))
        assertTrue(!LicensePolicy.allowsExport("Unknown (user-supplied)"))
        assertTrue(!LicensePolicy.allowsExport("irdb license"))
    }
}
