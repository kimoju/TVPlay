package app.tvplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAppearanceTest {
    @After fun resetRuntime() = AppAppearanceRuntime.resetForTest()

    @Test fun stableKeysRoundTripAndUnknownValuesDefaultToDark() {
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey(null))
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey("future-value"))
        AppAppearance.entries.forEach { appearance ->
            assertEquals(appearance, AppAppearance.fromKey(appearance.key))
        }
    }

    @Test fun explicitAppearancesIgnoreCarPlayAndHostState() {
        for (mode in CarPlayNightMode.entries) {
            assertTrue(resolveAppNight(AppAppearance.DARK, mode, false, false))
            assertFalse(resolveAppNight(AppAppearance.LIGHT, mode, true, true))
        }
    }

    @Test fun autoUsesTheLiveHostResultWhenAvailable() {
        assertTrue(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.DAY, false, true))
        assertFalse(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.NIGHT, true, false))
    }

    @Test fun autoFallsBackToTheSavedCarPlayPolicyOutsideTheHost() {
        assertFalse(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.DAY, true, null))
        assertTrue(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.NIGHT, false, null))
        assertTrue(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.SYSTEM, true, null))
        assertFalse(resolveAppNight(AppAppearance.AUTO, CarPlayNightMode.SYSTEM, false, null))
    }

    @Test fun runtimePublishesOnlyChangesAndProtectsTheCurrentOwner() {
        val firstOwner = Any()
        val secondOwner = Any()
        val observed = mutableListOf<Boolean?>()
        val stopObserving = AppAppearanceRuntime.observeHost(observed::add)

        assertEquals(listOf<Boolean?>(null), observed)
        AppAppearanceRuntime.publishHost(firstOwner, true)
        AppAppearanceRuntime.publishHost(firstOwner, true)
        AppAppearanceRuntime.publishHost(secondOwner, false)
        AppAppearanceRuntime.clearHost(firstOwner)
        assertEquals(false, AppAppearanceRuntime.hostNight())
        AppAppearanceRuntime.clearHost(secondOwner)
        assertNull(AppAppearanceRuntime.hostNight())
        assertEquals(listOf(null, true, false, null), observed)

        stopObserving()
        stopObserving()
        AppAppearanceRuntime.publishHost(firstOwner, true)
        assertEquals(listOf(null, true, false, null), observed)
    }

    @Test fun runtimeDoesNotHideObserverFailures() {
        val owner = Any()
        AppAppearanceRuntime.observeHost { night ->
            if (night != null) error("observer failed")
        }

        val failure = runCatching { AppAppearanceRuntime.publishHost(owner, true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("observer failed", failure?.message)
    }
}
