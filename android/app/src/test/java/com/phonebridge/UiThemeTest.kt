package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

class UiThemeTest {
    private fun catalog() = UiTheme.parseCatalog(
        InputStreamReader(javaClass.classLoader!!.getResourceAsStream("ui-themes.json")!!)
            .readText()
    )

    @Test
    fun sharedCatalogContainsAllThreeThemesAndDefaultsToNightCore() {
        val themes = catalog()
        assertEquals("noir", themes.defaultThemeId)
        assertEquals(listOf("glass", "liquid", "noir"), themes.themes.map { it.id })
        assertEquals(0xF2122344.toInt(), themes.themes.first().panel)
        themes.themes.forEach { theme ->
            assertTrue(theme.label.isNotBlank())
            assertTrue(theme.textPrimary != 0)
            assertTrue(theme.textSecondary != 0)
            assertTrue(theme.focus != 0)
        }
    }

    @Test
    fun existingAndroidSelectionsArePreservedAndUnknownIdsFallBackSafely() {
        val themes = catalog()
        assertEquals("glass", UiTheme.resolve("AURORA_GLASS", themes).id)
        assertEquals("liquid", UiTheme.resolve("LIQUID_MOTION", themes).id)
        assertEquals("noir", UiTheme.resolve("CIRCUIT_NOIR", themes).id)
        assertEquals("noir", UiTheme.resolve("future-theme", themes).id)
        assertEquals("noir", UiTheme.resolve(null, themes).id)
    }

    @Test
    fun everyThemeKeepsPrimaryAndSecondaryTextReadableOnItsPanels() {
        catalog().themes.forEach { theme ->
            listOf(theme.panel, theme.card, theme.input, theme.buttonFill).forEach { surface ->
                val composed = UiTheme.compositeColor(surface, theme.backgroundTop)
                assertTrue(UiTheme.contrastRatio(theme.textPrimary, composed) >= 4.5)
                assertTrue(UiTheme.contrastRatio(theme.textSecondary, composed) >= 4.5)
                assertTrue(UiTheme.contrastRatio(theme.buttonText, composed) >= 4.5)
            }
        }
    }
}
