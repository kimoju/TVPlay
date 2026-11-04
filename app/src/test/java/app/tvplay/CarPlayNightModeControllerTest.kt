package app.tvplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayNightModeControllerTest {
    @Test fun fixedAndSystemModesUpdateNightStateCleanly() {
        val output = mutableListOf<Boolean>()
        val controller = CarPlayNightModeController(initialNight = false, onNightChanged = output::add)

        controller.configure(CarPlayNightMode.DAY, true)
        assertFalse(controller.night)
        controller.systemChanged(true)
        assertFalse(controller.night)

        controller.configure(CarPlayNightMode.NIGHT, false)
        controller.systemChanged(false)
        assertTrue(controller.night)

        controller.configure(CarPlayNightMode.SYSTEM, false)
        assertFalse(controller.night)
        controller.systemChanged(true)
        assertTrue(controller.night)

        assertEquals(listOf(true, false, true), output)
    }
}
