package com.universalir.ir

import com.universalir.ir.core.IrPattern
import com.universalir.ir.core.PatternIssue
import com.universalir.ir.core.PatternValidator
import com.universalir.ir.core.TimingBuilder
import com.universalir.ir.format.BroadlinkCodec
import com.universalir.ir.format.CanonicalCodeSet
import com.universalir.ir.format.CanonicalImporter
import com.universalir.ir.format.CodeSource
import com.universalir.ir.format.CommandKind
import com.universalir.ir.format.CommandNames
import com.universalir.ir.format.DecodeResult
import com.universalir.ir.format.FlipperIrImporter
import com.universalir.ir.format.FormatDetector
import com.universalir.ir.format.ImportContext
import com.universalir.ir.format.IrdbCsvImporter
import com.universalir.ir.format.ProntoCodec
import com.universalir.ir.format.SmartIrClimateImporter
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.consumer.NecProtocol
import com.universalir.ir.protocol.consumer.Rc5Protocol
import com.universalir.ir.protocol.consumer.Samsung32Protocol
import com.universalir.ir.protocol.consumer.SircProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatAndValidationTest {
    private val src = CodeSource("test", "https://example.invalid", license = "test")
    private fun ctx(cat: DeviceCategory = DeviceCategory.TELEVISION) = ImportContext("t", "Brand", cat, source = src)

    // ------------------------------------------------------------ validation

    @Test
    fun validatorAcceptsNormalPattern() {
        val p = IrPattern(38_000, intArrayOf(9000, 4500, 560, 560, 560))
        assertTrue(PatternValidator.validate(p).isEmpty())
    }

    @Test
    fun validatorRejectsBadCarrierDurationsAndLength() {
        assertTrue(PatternValidator.validate(IrPattern(1_000, intArrayOf(500))).any { it is PatternIssue.CarrierOutOfRange })
        assertTrue(PatternValidator.validate(IrPattern(38_000, intArrayOf(500, 0, 500))).any { it is PatternIssue.InvalidDuration })
        assertTrue(PatternValidator.validate(IrPattern(38_000, intArrayOf())).any { it is PatternIssue.Empty })
        val long = IntArray(9) { 250_000 }
        assertTrue(PatternValidator.validate(IrPattern(38_000, long)).any { it is PatternIssue.TooLong })
        assertTrue(PatternValidator.validate(IrPattern(38_000, IntArray(3000) { 100 })).any { it is PatternIssue.TooManyEntries })
    }

    @Test
    fun validatorChecksHardwareCarrierRanges() {
        val p = IrPattern(56_000, intArrayOf(500, 500, 500))
        val issues = PatternValidator.validate(p, listOf(30_000..40_000))
        assertTrue(issues.single() is PatternIssue.CarrierNotSupportedByHardware)
        assertTrue(PatternValidator.validate(p, listOf(30_000..60_000)).isEmpty())
    }

    @Test
    fun timingBuilderMergesSamePolarityAndDropsEdges() {
        val p = TimingBuilder().space(100).mark(10).mark(20).space(5).space(5).mark(1).space(999).build(38_000)
        assertEquals(listOf(30, 10, 1), p.timings().toList())
    }

    // ------------------------------------------------------------ consumer protocols

    @Test
    fun necEncodesAddressCommandAndInverses() {
        val r = NecProtocol.encode(0x04, 0x08) as EncodeResult.Success
        assertEquals("F708FB04", r.payloads.single())
        val t = r.transmission.messages.single().pattern.timings()
        assertEquals(67, t.size)
        assertEquals(9000, t[0]); assertEquals(4500, t[1])
    }

    @Test
    fun samsung32RepeatsAddress() {
        val r = Samsung32Protocol.encode(0x07, 0x02) as EncodeResult.Success
        assertEquals("FD020707", r.payloads.single())
        assertEquals(4500, r.transmission.messages.single().pattern.timings()[0])
    }

    @Test
    fun sircSendsThreeFramesAt40kHz() {
        val r = SircProtocol(12).encode(1, 21) as EncodeResult.Success
        val p = r.transmission.messages.single().pattern
        assertEquals(40_000, p.carrierHz)
        assertEquals(3, p.timings().count { it == 2400 })
    }

    @Test
    fun rc5RejectsOutOfRangeAndEncodesManchester() {
        assertTrue(Rc5Protocol(false).encode(40, 1) is EncodeResult.Failure)
        val r = Rc5Protocol(false).encode(0, 12) as EncodeResult.Success
        val t = r.transmission.messages.single().pattern.timings()
        assertTrue(t.all { it == 889 || it == 1778 })
    }

    // ------------------------------------------------------------ raw formats

    @Test
    fun broadlinkRejectsRfPackets() {
        val rf = byteArrayOf(0xB2.toByte(), 0, 2, 0, 10, 10)
        assertTrue(BroadlinkCodec.decode(rf) is DecodeResult.Error)
    }

    @Test
    fun prontoDecodesLearnedCode() {
        // 38 kHz (0x006D), one burst pair of 0x0010/0x0020 units.
        val r = ProntoCodec.decode("0000 006D 0001 0000 0010 0020") as DecodeResult.Ok
        assertTrue(r.value.carrierHz in 37_500..38_500)
        assertEquals(1, r.value.size) // trailing space is dropped
        assertTrue(ProntoCodec.decode("0100 006D 0001 0000 0010 0020") is DecodeResult.Error)
    }

    // ------------------------------------------------------------ importers

    @Test
    fun flipperRawAcFileImports() {
        val text = TestSupport.resource("flipper/electra_smart.ir")
        assertEquals(FormatDetector.Kind.FLIPPER, FormatDetector.detect(text))
        val report = FlipperIrImporter.import(text, ctx(DeviceCategory.AIR_CONDITIONER))
        assertNull(report.fatal)
        assertEquals(2, report.accepted)
        val on = report.codeSet!!.commands.first { it.name == CommandNames.POWER_ON }
        assertEquals(CommandKind.RAW, on.kind)
        assertEquals(38_000, on.carrierHz)
        // The Electra Smart remote sends the 104-bit 0xC3 Electra protocol.
        val bytes = TestSupport.lsbBytes(TestSupport.pulseDistanceBits(on.timings!!.toIntArray(), 2, 104))
        assertEquals(0xC3, bytes[0])
    }

    @Test
    fun flipperParsedTvFileImports() {
        val report = FlipperIrImporter.import(TestSupport.resource("flipper/samsung_tv.ir"), ctx())
        assertNull(report.fatal)
        assertTrue(report.accepted > 5)
        val power = report.codeSet!!.commands.first { it.name == CommandNames.POWER }
        assertEquals("Samsung32", power.protocol)
    }

    @Test
    fun flipperRejectsUnknownProtocolsAndGarbage() {
        val text = """
            Filetype: IR signals file
            Version: 1
            name: A
            type: parsed
            protocol: Pioneer
            address: 01 00 00 00
            command: 02 00 00 00
            name: B
            type: raw
            frequency: 38000
            duty_cycle: 0.33
            data: 100 -5 x
        """.trimIndent()
        val report = FlipperIrImporter.import(text, ctx())
        assertNotNull(report.fatal)
        assertEquals(2, report.rejected.size)
        assertNotNull(FlipperIrImporter.import("hello", ctx()).fatal)
    }

    @Test
    fun irdbCsvMapsNecVariants() {
        // Self-authored fixture in IRDB's column format (not copied from IRDB).
        val csv = """
            functionname,protocol,device,subdevice,function
            POWER,NECx2,7,7,2
            VOLUME +,NEC1,4,-1,2
            EXT,NEC1,4,1,2
            SONY POWER,Sony12,1,-1,21
            MYSTERY,Kaseikyo,1,2,3
        """.trimIndent()
        val report = IrdbCsvImporter.import(csv, ctx())
        assertEquals(4, report.accepted)
        assertEquals(1, report.rejected.size)
        val cmds = report.codeSet!!.commands.associateBy { it.name }
        assertEquals("Samsung32", cmds[CommandNames.POWER]!!.protocol)
        assertEquals("NEC", cmds[CommandNames.VOL_UP]!!.protocol)
        assertEquals("NECext", cmds["ext"]!!.protocol)
        assertEquals(0xFD02L, cmds["ext"]!!.command)
    }

    @Test
    fun smartIrImportsCapturedTables() {
        val report = SmartIrClimateImporter.import(TestSupport.resource("smartir/1340.json"), ctx(DeviceCategory.AIR_CONDITIONER))
        assertNull(report.fatal)
        assertTrue(report.accepted > 100)
        val keys = report.codeSet!!.commands.map { it.name }.toSet()
        assertTrue("off" in keys)
        assertTrue("cool/medium/24" in keys)
    }

    @Test
    fun canonicalRoundTripRevalidates() {
        val set = SmartIrClimateImporter.import(TestSupport.resource("smartir/1342.json"), ctx(DeviceCategory.AIR_CONDITIONER)).codeSet!!
        val text = CanonicalCodeSet.json.encodeToString(CanonicalCodeSet.serializer(), set)
        assertEquals(FormatDetector.Kind.CANONICAL_JSON, FormatDetector.detect(text))
        val back = CanonicalImporter.import(text)
        assertEquals(set.commands.size, back.accepted)
        val tampered = text.replaceFirst("\"timings\":[", "\"timings\":[0,")
        assertEquals(set.commands.size - 1, CanonicalImporter.import(tampered).accepted)
    }

    @Test
    fun commandNamesNormalise() {
        assertEquals(CommandNames.VOL_UP, CommandNames.normalise("Vol+"))
        assertEquals(CommandNames.VOL_UP, CommandNames.normalise("VOLUME +"))
        assertEquals(CommandNames.POWER, CommandNames.normalise("Power"))
        assertEquals("digit_5", CommandNames.normalise("5"))
        assertEquals(CommandNames.INPUT, CommandNames.normalise("Source"))
    }
}
