package com.universalir

import com.universalir.data.catalog.BundledCatalog
import com.universalir.domain.usecase.CodeTester
import com.universalir.ir.format.CanonicalImporter
import com.universalir.ir.format.CanonicalRenderer
import com.universalir.ir.format.CodeSource
import com.universalir.ir.format.CommandNames
import com.universalir.ir.format.FlipperIrImporter
import com.universalir.ir.format.ImportContext
import com.universalir.ir.format.ImportReport
import com.universalir.ir.format.SmartIrClimateImporter
import com.universalir.ir.format.SmartIrDeviceImporter
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.EncodeResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parses the bundled catalogue exactly as CatalogSeeder / CodeRepository do, from the real asset files. */
class BundledCatalogTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { File(it, "catalog/catalog.json").exists() }
    private val catalog = Json { ignoreUnknownKeys = true }
        .decodeFromString(BundledCatalog.serializer(), File(assets, "catalog/catalog.json").readText())

    private fun load(id: String): ImportReport {
        val e = catalog.entries.single { it.id == id }
        val text = File(assets, e.asset).readText()
        val ctx = ImportContext(e.id, e.brand, DeviceCategory.valueOf(e.category), source = CodeSource("t", "", license = "t"))
        return when (e.format) {
            "canonical" -> CanonicalImporter.import(text)
            "flipper_ir" -> FlipperIrImporter.import(text, ctx)
            "smartir_climate" -> SmartIrClimateImporter.import(text, ctx)
            else -> SmartIrDeviceImporter.import(text, ctx)
        }
    }

    @Test
    fun catalogParsesAndEveryEntryLoads() {
        assertTrue(catalog.version >= 2)
        assertEquals(catalog.entries.size, catalog.entries.map { it.id }.toSet().size)
        val failing = catalog.entries.mapNotNull { e ->
            DeviceCategory.valueOf(e.category)
            assertTrue(e.asset, File(assets, e.asset).exists())
            val report = load(e.id)
            if (report.codeSet == null || report.accepted == 0) "${e.id}: ${report.fatal}" else null
        }
        // tools/build_catalog.py drops files over the 2 MB cap and files whose codes are not IR
        // (Broadlink RF), so every bundled entry must load.
        assertEquals(emptyList<String>(), failing)
    }

    @Test
    fun swingNestedClimateTablesLoadEveryState() {
        // SmartIR 1401 nests mode/fan/swing/temp; before the importer fix only "off" loaded.
        val set = load("smartir-climate-1401").codeSet!!
        val names = set.commands.map { it.name }
        assertTrue(names.size > 20)
        assertTrue(names.any { it.startsWith("cool/") && it.count { c -> c == '/' } == 2 })
    }

    @Test
    fun fujicomCandidatesAreUnverifiedCopiesOfGenericAndroidTvSets() {
        val androidTv = catalog.entries.filter { "Generic Android TV" in it.extraBrands }
        assertTrue(androidTv.size >= 5)
        val fujicom = catalog.entries.filter { it.brand == "Fujicom" }
        assertEquals(androidTv.size, fujicom.size)
        for (e in fujicom) {
            assertEquals(DeviceCategory.TELEVISION.name, e.category)
            assertTrue(e.id, e.evidence!!.startsWith("UNVERIFIED"))
            assertTrue(e.id, androidTv.any { it.asset == e.asset })
            assertNotNull(e.id, load(e.id).codeSet)
        }
        val edison = load(androidTv.single { it.format == "flipper_ir" }.id).codeSet!!
        assertTrue(edison.commands.map { it.name }.containsAll(listOf(CommandNames.POWER, CommandNames.OK, CommandNames.HOME, CommandNames.APPS, CommandNames.NETFLIX, CommandNames.YOUTUBE)))
    }

    @Test
    fun everyBundledEntryHasAnExportableLicense() {
        for (e in catalog.entries) {
            val license = e.license ?: catalog.license
            assertTrue("${e.id}: $license", license == "Factual protocol values" || com.universalir.domain.model.LicensePolicy.allowsExport(license))
        }
    }

    @Test
    fun ledRemotesAreLightSetsThatEncode() {
        val led = catalog.entries.filter { it.id.startsWith("led-") }
        assertTrue(led.size >= 2)
        for (e in led) {
            assertEquals(DeviceCategory.LIGHT.name, e.category)
            val set = load(e.id).codeSet!!
            assertEquals(DeviceCategory.LIGHT, set.category)
            assertTrue(e.id, set.commands.map { it.name }.containsAll(listOf(CommandNames.BRIGHTNESS_UP, CommandNames.BRIGHTNESS_DOWN, "color:red", "color:white")))
            assertTrue(e.id, CommandNames.POWER_ON in set.commands.map { it.name } || CommandNames.POWER in set.commands.map { it.name })
            for (c in set.commands) {
                val r = CanonicalRenderer.render(c)
                assertTrue("${e.id}/${c.name}", r is EncodeResult.Success)
                val t = (r as EncodeResult.Success).transmission.messages.single().pattern.timings()
                assertEquals(67, t.size); assertEquals(9000, t[0]); assertEquals(4500, t[1])
            }
        }
        val key24 = load("led-24key-necext-ef00").codeSet!!
        assertEquals(24, key24.commands.size)
        val on = key24.commands.single { it.name == CommandNames.POWER_ON }
        assertEquals(0xEF00L, on.address); assertEquals(0xFC03L, on.command)
        assertEquals(44, load("led-44key-nec-00").codeSet!!.commands.size)
    }

    @Test
    fun linuxKernelBoxKeymapsLoadAndEncodePowerAndOk() {
        val kernel = catalog.entries.filter { it.id.startsWith("kernel-") }
        assertTrue(kernel.size >= 18)
        for (e in kernel) {
            assertEquals(DeviceCategory.TV_BOX.name, e.category)
            assertTrue(e.id, e.license!!.startsWith("GPL-2.0") && e.license!!.contains("Linux kernel, v"))
            assertTrue(e.id, com.universalir.domain.model.LicensePolicy.allowsExport(e.license!!))
            if (!e.id.startsWith("kernel-dreambox")) assertTrue(e.id, "Unknown / generic Chinese box" in e.extraBrands)
            val set = load(e.id).codeSet!!
            val names = set.commands.map { it.name }
            assertEquals(e.id, names.size, names.toSet().size)
            for (n in listOf(CommandNames.POWER, CommandNames.OK)) {
                val cmd = set.commands.single { it.name == n }
                val r = CanonicalRenderer.render(cmd)
                assertTrue("${e.id}/$n", r is EncodeResult.Success)
                val t = (r as EncodeResult.Success).transmission.messages.single().pattern.timings()
                assertEquals(67, t.size); assertEquals(9000, t[0])
            }
            set.commands.forEach { assertTrue("${e.id}/${it.name}", CanonicalRenderer.render(it) is EncodeResult.Success) }
        }
        // X96 Max power is kernel scancode 0x140: NEC address 0x01, command 0x40.
        val x96 = load("kernel-x96max").codeSet!!.commands.single { it.name == CommandNames.POWER }
        assertEquals("NEC", x96.protocol); assertEquals(0x01L, x96.address); assertEquals(0x40L, x96.command)
        // Tanix TX5 max power is NECX 0x40404d: address bytes 40 40 -> 0x4040, command 0x4d + inverse.
        val tx5 = load("kernel-tanix-tx5max").codeSet!!.commands.single { it.name == CommandNames.POWER }
        assertEquals("NECext", tx5.protocol); assertEquals(0x4040L, tx5.address); assertEquals(0xB24DL, tx5.command)
    }

    private val arrows = listOf(CommandNames.UP, CommandNames.DOWN, CommandNames.LEFT, CommandNames.RIGHT, CommandNames.OK)

    private fun firstTimings(set: com.universalir.ir.format.CanonicalCodeSet, name: String): IntArray {
        val r = CanonicalRenderer.render(set.commands.single { it.name == name })
        assertTrue("${set.id}/$name", r is EncodeResult.Success)
        return (r as EncodeResult.Success).transmission.messages.first().pattern.timings()
    }

    @Test
    fun samsungAndLgFullRemotesHaveSmartKeysAndArrows() {
        val samsung = load("fullremote-samsung-tv").codeSet!!
        val lg = load("fullremote-lg-tv").codeSet!!
        for (set in listOf(samsung, lg)) {
            val names = set.commands.map { it.name }
            assertTrue(set.id, names.containsAll(arrows + listOf(CommandNames.POWER, CommandNames.BACK, CommandNames.HOME, CommandNames.NETFLIX, CommandNames.MUTE, CommandNames.VOL_UP, "digit_0", "digit_9")))
            assertTrue(set.id, set.commands.all { CanonicalRenderer.render(it) is EncodeResult.Success })
        }
        for (n in listOf(CommandNames.POWER, CommandNames.UP, CommandNames.OK, CommandNames.NETFLIX)) {
            val s = firstTimings(samsung, n)
            assertEquals(67, s.size); assertEquals(4500, s[0]); assertEquals(4500, s[1])
            val l = firstTimings(lg, n)
            assertEquals(67, l.size); assertEquals(9000, l[0]); assertEquals(4500, l[1])
        }
        fun cmd(set: com.universalir.ir.format.CanonicalCodeSet, n: String) = set.commands.single { it.name == n }
        assertEquals("Samsung32", cmd(samsung, CommandNames.POWER).protocol)
        assertEquals(0x07L, cmd(samsung, CommandNames.POWER).address); assertEquals(0x02L, cmd(samsung, CommandNames.POWER).command)
        assertEquals(0x79L, cmd(samsung, CommandNames.HOME).command); assertEquals(0xF3L, cmd(samsung, CommandNames.NETFLIX).command)
        assertEquals(0x68L, cmd(samsung, CommandNames.OK).command); assertEquals(0x58L, cmd(samsung, CommandNames.BACK).command)
        assertEquals("NEC", cmd(lg, CommandNames.POWER).protocol)
        assertEquals(0x04L, cmd(lg, CommandNames.POWER).address); assertEquals(0x08L, cmd(lg, CommandNames.POWER).command)
        assertEquals(0x44L, cmd(lg, CommandNames.OK).command); assertEquals(0x56L, cmd(lg, CommandNames.NETFLIX).command)
        assertEquals(0x7CL, cmd(lg, CommandNames.HOME).command); assertEquals(0x28L, cmd(lg, CommandNames.BACK).command)
    }

    @Test
    fun fullRemotesLoadEncodeAndSortFirstInTheirBrand() {
        val full = catalog.entries.filter { it.id.startsWith("fullremote-") }
        assertTrue(full.size >= 10)
        for (e in full) {
            assertEquals(e.id, "Factual protocol values", e.license)
            val set = load(e.id).codeSet!!
            assertEquals(e.id, DeviceCategory.valueOf(e.category), set.category)
            val names = set.commands.map { it.name }
            assertEquals(e.id, names.size, names.toSet().size)
            assertEquals(e.id, 0, load(e.id).rejected.size)
            set.commands.forEach { assertTrue("${e.id}/${it.name}", CanonicalRenderer.render(it) is EncodeResult.Success) }
            if (e.category != DeviceCategory.AUDIO.name) assertTrue(e.id, names.containsAll(arrows))
            assertTrue(e.id, CommandNames.POWER in names || e.id == "fullremote-apple-tv")
            // CatalogDao.codeSetRows orders by title: "All ..." precedes "Flipper-IRDB ..." and "SmartIR ...".
            assertTrue(e.id, e.title!!.startsWith("All ") && e.title!! < "Flipper-IRDB" && e.title!! < "SmartIR")
        }
    }

    @Test
    fun lightsTestWithOnAndGenericBoxBrandGroupsBoxes() {
        assertEquals(CommandNames.POWER_ON, CodeTester.testCommandOrder(DeviceCategory.LIGHT).first())
        val generic = catalog.entries.filter { "Unknown / generic Chinese box" in it.extraBrands }
        assertTrue(generic.size >= 3)
        assertTrue(generic.all { it.category == DeviceCategory.TV_BOX.name })
        val mag = load(generic.single { it.format == "flipper_ir" }.id).codeSet!!
        assertTrue(mag.commands.map { it.name }.containsAll(listOf(CommandNames.POWER, CommandNames.OK, CommandNames.HOME, CommandNames.SETTINGS, CommandNames.NETFLIX)))
    }
}
