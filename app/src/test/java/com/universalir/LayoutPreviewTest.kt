package com.universalir

import com.universalir.data.catalog.BundledCatalog
import com.universalir.feature.remote.defaultLayout
import com.universalir.feature.remote.standardColumns
import com.universalir.ir.format.CanonicalImporter
import com.universalir.ir.format.CodeSource
import com.universalir.ir.format.FlipperIrImporter
import com.universalir.ir.format.ImportContext
import com.universalir.ir.format.SmartIrDeviceImporter
import com.universalir.ir.protocol.DeviceCategory
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/** Prints the standard layout of real bundled remotes as text grids (build/layout-preview.txt) for review. */
class LayoutPreviewTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { File(it, "catalog/catalog.json").exists() }
    private val catalog = Json { ignoreUnknownKeys = true }.decodeFromString(BundledCatalog.serializer(), File(assets, "catalog/catalog.json").readText())

    @Test fun preview() {
        val out = StringBuilder()
        val ids = catalog.entries.filter { it.category in setOf("TELEVISION", "TV_BOX", "LIGHT", "AUDIO") }
            .filter { it.id in setOf("smartir-media_player-1060", "kernel-x96max", "kernel-mecool-kii-pro", "led-24key-necext-ef00") || it.brand.startsWith("Unknown") }
            .take(6)
        for (e in ids) {
            val ctx = ImportContext(e.id, e.brand, DeviceCategory.valueOf(e.category), source = CodeSource("t", "", license = "t"))
            val text = File(assets, e.asset).readText()
            val set = when (e.format) {
                "canonical" -> CanonicalImporter.import(text)
                "flipper_ir" -> FlipperIrImporter.import(text, ctx)
                else -> SmartIrDeviceImporter.import(text, ctx)
            }.codeSet ?: continue
            val names = set.commands.map { it.name }
            val cat = DeviceCategory.valueOf(e.category)
            val cols = standardColumns(names, cat)
            val layout = defaultLayout(names, { n -> set.commands.first { it.name == n }.label }, cols, cat)
            out.appendLine("== ${e.id} (${e.brand}) ${names.size} commands")
            out.appendLine("   names: $names")
            val rows = layout.maxOf { it.row + it.h }
            for (r in 0 until rows) {
                val line = (0 until cols).joinToString(" | ") { c ->
                    val b = layout.firstOrNull { r in it.row until it.row + it.h && c in it.col until it.col + it.w }
                    (b?.commandName ?: ".").take(10).padEnd(10)
                }
                out.appendLine("   $line")
            }
        }
        File("build/layout-preview.txt").writeText(out.toString())
    }
}
