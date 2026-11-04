package app.tvplay.ui

import app.tvplay.ui.theme.AppAppearance
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.workbench.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvUiTest {

    @Test
    fun tvColorSchemeProvidesDistinctDarkAndLightTenFootPalettes() {
        val dark = TvColorScheme.of(true)
        val light = TvColorScheme.of(false)
        assertTrue(dark.night)
        assertFalse(light.night)
        assertNotEquals(dark.background, light.background)
        assertNotEquals(dark.primaryText, light.primaryText)
        assertNotEquals(0, dark.focusRing)
    }

    @Test
    fun appAppearanceResolvesNightFlagDeterministically() {
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey("dark"))
        assertEquals(AppAppearance.LIGHT, AppAppearance.fromKey("light"))
        assertEquals(AppAppearance.AUTO, AppAppearance.fromKey("auto"))
    }

    @Test
    fun workbenchSettingsTabsExposeExpectedFourSectionOrder() {
        val tabs = SettingsCategory.entries
        assertEquals(
            listOf(
                SettingsCategory.CONNECTION,
                SettingsCategory.DISPLAY,
                SettingsCategory.AUDIO,
                SettingsCategory.ABOUT,
            ),
            tabs,
        )
    }
}
