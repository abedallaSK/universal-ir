package com.universalir

import com.universalir.domain.model.CustomButton
import com.universalir.feature.remote.defaultLayout
import com.universalir.feature.remote.dpadCentre
import com.universalir.ir.protocol.DeviceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLayoutTest {
    private val tv = listOf(
        "power", "input", "mute", "up", "down", "left", "right", "ok", "back", "home", "menu", "vol_up", "vol_down", "ch_up", "ch_down",
        "play", "pause", "stop", "fast_forward", "rewind", "red", "green", "yellow", "blue", "youtube", "netflix", "info", "exit",
    ) + (0..9).map { "digit_$it" } + listOf("sleep", "aspect", "favorites")

    private val light = listOf("power_on", "power_off", "brightness_up", "brightness_down", "color:red", "color:green", "color:blue",
        "color:white", "color:orange", "effect:flash", "effect:fade", "speed_up", "speed_down")

    private fun cells(b: CustomButton) = (b.row until b.row + b.h).flatMap { r -> (b.col until b.col + b.w).map { r to it } }

    private fun check(layout: List<CustomButton>, commands: List<String>, columns: Int) {
        assertEquals("every command exactly once", commands.sorted(), layout.map { it.commandName }.sorted())
        val all = layout.flatMap { cells(it) }
        assertEquals("no overlapping keys", all.size, all.toSet().size)
        assertTrue("inside the grid", layout.all { it.row >= 0 && it.col >= 0 && it.col + it.w <= columns })
    }

    @Test fun tvLayoutIsCompleteAndNonOverlappingForEveryColumnCount() {
        for (columns in 3..5) {
            val layout = defaultLayout(tv, { it }, columns, DeviceCategory.TELEVISION)
            check(layout, tv, columns)
            assertNotNull("D-pad cross recognised at $columns columns", dpadCentre(layout))
            assertEquals("power is first", 0, layout.first { it.commandName == "power" }.row)
        }
    }

    @Test fun lightLayoutGivesColourKeysTheirColour() {
        val layout = defaultLayout(light, { it }, 4, DeviceCategory.LIGHT)
        check(layout, light, 4)
        assertTrue(layout.filter { it.commandName.startsWith("color:") }.all { it.rgb != null })
    }

    @Test fun fanAndSparseSetsStillPlaceEverything() {
        val fan = listOf("power", "speed", "oscillate", "timer", "speed:low", "speed:high")
        check(defaultLayout(fan, { it }, 4, DeviceCategory.FAN), fan, 4)
        val sparse = listOf("power", "vol_up")
        check(defaultLayout(sparse, { it }, 3), sparse, 3)
    }
}
