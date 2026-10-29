package com.universalir.widget

import com.universalir.domain.usecase.AcStateRules
import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.ac.AcCapabilities
import com.universalir.ir.protocol.ac.AcMode
import com.universalir.ir.protocol.ac.AcState
import com.universalir.ir.protocol.ac.FanSpeed
import com.universalir.ir.protocol.ac.PowerControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLogicTest {

    @Test fun tvButtonsKeepPriorityAndOnlyAvailable() {
        val cmds = WidgetButtons.buttons(DeviceCategory.TELEVISION, setOf("vol_up", "power", "mute", "vol_down", "ch_up", "power_on", "netflix"))
            .map { it.command }
        assertEquals(listOf("power", "mute", "vol_down", "vol_up", "ch_up"), cmds)
    }

    @Test fun separateOnOffShownWithoutPowerToggle() {
        val cmds = WidgetButtons.buttons(DeviceCategory.TELEVISION, setOf("power_on", "power_off", "mute")).map { it.command }
        assertEquals(listOf("mute", "power_on", "power_off"), cmds)
    }

    @Test fun lightLayoutAndColors() {
        val available = setOf("power_on", "power_off", "brightness_up", "brightness_down", "color:blue", "color:red", "color:green", "color:white", "color:pink")
        assertEquals(listOf("power_on", "power_off", "brightness_down", "brightness_up"),
            WidgetButtons.buttons(DeviceCategory.OTHER, available).map { it.command })
        assertEquals(listOf("color:red", "color:green", "color:blue", "color:white"), WidgetButtons.colors(available).map { it.first })
    }

    @Test fun dpadNeedsAllFive() {
        assertTrue(WidgetButtons.hasDpad(setOf("up", "down", "left", "right", "ok")))
        assertFalse(WidgetButtons.hasDpad(setOf("up", "down", "left", "right")))
    }

    @Test fun acStepRespectsRangeAndIgnoredModes() {
        val caps = AcCapabilities(16..30, setOf(AcMode.COOL, AcMode.FAN_ONLY), setOf(FanSpeed.AUTO),
            temperatureIgnoredIn = setOf(AcMode.FAN_ONLY), powerControl = PowerControl.ABSOLUTE)
        assertEquals(25, AcStateRules.stepTemperature(AcState(true, AcMode.COOL, 24), caps, null, up = true))
        assertNull(AcStateRules.stepTemperature(AcState(true, AcMode.COOL, 30), caps, null, up = true))
        assertNull(AcStateRules.stepTemperature(AcState(true, AcMode.FAN_ONLY, 24), caps, null, up = false))
        val table = { _: AcMode, _: FanSpeed -> listOf(18, 22, 26) }
        assertEquals(22, AcStateRules.stepTemperature(AcState(true, AcMode.COOL, 26), caps, table, up = false))
        assertEquals(26, AcStateRules.coerce(AcState(true, AcMode.HEAT, 27), caps, table).temperatureCelsius)
        assertEquals(AcMode.COOL, AcStateRules.coerce(AcState(true, AcMode.HEAT, 27), caps, table).mode)
    }
}
