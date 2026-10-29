package com.universalir.ir

import com.universalir.ir.core.PatternValidator
import com.universalir.ir.format.CanonicalCodeSet
import com.universalir.ir.format.CanonicalCommand
import com.universalir.ir.format.CanonicalImporter
import com.universalir.ir.format.CanonicalRenderer
import com.universalir.ir.format.CodeSource
import com.universalir.ir.format.CommandKind
import com.universalir.ir.format.CommandNames
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.EncodeResult
import com.universalir.ir.protocol.consumer.NecExtProtocol
import com.universalir.ir.protocol.consumer.NecProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LED strip / bulb remotes: NEC and NECext encoding of the bundled light code tables. */
class LightCodesTest {

    /** Decodes a 67-entry NEC-family pattern back into its 4 bytes (LSB first). */
    private fun necBytes(timings: IntArray): List<Int> {
        assertEquals(67, timings.size)
        assertEquals(9000, timings[0]); assertEquals(4500, timings[1])
        for (i in 2 until timings.size step 2) assertEquals(560, timings[i])
        var v = 0L
        for (bit in 0 until 32) {
            val space = timings[3 + 2 * bit]
            assertTrue("space $space", space == 560 || space == 1690)
            if (space == 1690) v = v or (1L shl bit)
        }
        return (0 until 4).map { ((v shr (8 * it)) and 0xFF).toInt() }
    }

    private fun necextCommand(cmd: Int) = (cmd or ((cmd xor 0xFF) shl 8)).toLong()

    @Test
    fun led24KeyOnEncodesAsNecExtEf00() {
        // 24-key remote "ON": NECext address 0xEF00, command 0x03 (+ inverse 0xFC).
        val r = NecExtProtocol.encode(0xEF00, necextCommand(0x03)) as EncodeResult.Success
        val pattern = r.transmission.messages.single().pattern
        assertEquals(38_000, pattern.carrierHz)
        assertTrue(PatternValidator.validate(pattern).isEmpty())
        // Bytes on air: 00 EF 03 FC, i.e. the classic 0xF7C03F (MSB-first notation).
        assertEquals(listOf(0x00, 0xEF, 0x03, 0xFC), necBytes(pattern.timings()))
        assertEquals("FC03EF00", r.payloads.single())
    }

    @Test
    fun all24KeyCommandsAreValidAndDistinct() {
        val frames = (0x00..0x17).map { c ->
            val r = NecExtProtocol.encode(0xEF00, necextCommand(c)) as EncodeResult.Success
            val p = r.transmission.messages.single().pattern
            assertTrue(PatternValidator.validate(p).isEmpty())
            val bytes = necBytes(p.timings())
            assertEquals(c, bytes[2]); assertEquals(c xor 0xFF, bytes[3])
            bytes
        }
        assertEquals(24, frames.distinct().size)
    }

    @Test
    fun led44KeyPowerAndBrightnessEncodeAsNecAddressZero() {
        // 0xFF02FD (power) and 0xFF3AC5 (brightness up) in MSB-first notation.
        for ((cmd, msbFirst) in listOf(0x40 to 0x00FF02FDL, 0x5C to 0x00FF3AC5L, 0x5D to 0x00FFBA45L)) {
            val r = NecProtocol.encode(0x00, cmd.toLong()) as EncodeResult.Success
            val bytes = necBytes(r.transmission.messages.single().pattern.timings())
            assertEquals(listOf(0x00, 0xFF, cmd, cmd xor 0xFF), bytes)
            val msb = bytes.fold(0L) { acc, b -> (acc shl 8) or Integer.reverse(b).ushr(24).toLong() }
            assertEquals(msbFirst, msb)
        }
    }

    @Test
    fun canonicalLightSetRoundTripsAndRenders() {
        val cmds = listOf(
            CanonicalCommand(CommandNames.POWER_ON, "On", CommandKind.PARSED, protocol = "NECext", address = 0xEF00, command = necextCommand(0x03)),
            CanonicalCommand("${CommandNames.COLOR_PREFIX}red", "Red", CommandKind.PARSED, protocol = "NECext", address = 0xEF00, command = necextCommand(0x04)),
            CanonicalCommand("${CommandNames.EFFECT_PREFIX}flash", "Flash", CommandKind.PARSED, protocol = "NECext", address = 0xEF00, command = necextCommand(0x0B)),
        )
        val set = CanonicalCodeSet(id = "led", protocolId = "NECext", category = DeviceCategory.LIGHT, brand = "Generic",
            commands = cmds, source = CodeSource("t", "https://example.invalid", license = "test"))
        val json = CanonicalCodeSet.json.encodeToString(CanonicalCodeSet.serializer(), set)
        assertTrue(json.contains("\"LIGHT\""))
        val back = CanonicalImporter.import(json)
        assertNotNull(back.codeSet)
        val parsed = back.codeSet!!
        assertEquals(DeviceCategory.LIGHT, parsed.category)
        assertEquals(3, parsed.commands.size)
        parsed.commands.forEach { assertTrue(CanonicalRenderer.render(it) is EncodeResult.Success) }
    }

    @Test
    fun smartIrLightFileMapsNamesAndSendsListsAsOneSequence() {
        val on = "[9000, -4500, 560, -560, 560]"
        val json = """
            {"manufacturer": "X", "supportedModels": ["L"], "supportedController": "ESPHome", "commandsEncoding": "Raw",
             "commands": {"on": "$on", "off": ["$on", "$on"], "brighten": "$on", "dim": "$on", "colder": "$on", "warmer": "$on", "night": "$on"}}
        """.trimIndent()
        val report = com.universalir.ir.format.SmartIrDeviceImporter.import(json,
            com.universalir.ir.format.ImportContext("l", "X", DeviceCategory.LIGHT, source = CodeSource("t", "", license = "t")))
        val set = report.codeSet!!
        assertEquals(DeviceCategory.LIGHT, set.category)
        assertEquals(
            setOf(CommandNames.POWER_ON, CommandNames.POWER_OFF, CommandNames.BRIGHTNESS_UP, CommandNames.BRIGHTNESS_DOWN,
                CommandNames.COLOR_TEMP_COOL, CommandNames.COLOR_TEMP_WARM, CommandNames.NIGHT_LIGHT),
            set.commands.map { it.name }.toSet(),
        )
        val off = set.commands.single { it.name == CommandNames.POWER_OFF }
        assertEquals(listOf(9000, 4500, 560, 560, 560, 50_000, 9000, 4500, 560, 560, 560), off.timings)
        assertEquals(listOf(0, 6), off.frameStarts)
    }

    @Test
    fun newCommandNamesNormalise() {
        assertEquals(CommandNames.BRIGHTNESS_UP, CommandNames.normalise("Brightness +"))
        assertEquals(CommandNames.BRIGHTNESS_DOWN, CommandNames.normalise("dim"))
        assertEquals(CommandNames.SETTINGS, CommandNames.normalise("SETTINGS"))
        assertEquals(CommandNames.EXIT, CommandNames.normalise("Exit"))
        assertEquals(CommandNames.BACK, CommandNames.normalise("Return"))
        assertEquals(CommandNames.CH_UP, CommandNames.normalise("Chan_next"))
        assertEquals(CommandNames.INPUT, CommandNames.normalise("SOURCES"))
        assertEquals(CommandNames.YOUTUBE, CommandNames.normalise("YouTube"))
        assertEquals(CommandNames.PAGE_DOWN, CommandNames.normalise("Page -"))
        assertEquals(CommandNames.RED, CommandNames.normalise("RED"))
        assertEquals(CommandNames.MENU, CommandNames.normalise("Menu"))
    }
}
