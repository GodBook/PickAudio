package com.pickaudio

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pickaudio.data.model.ThemeColor
import com.pickaudio.data.model.ThemeMode
import com.pickaudio.data.preferences.UserPreferences
import com.pickaudio.ui.screens.SettingsScreen
import com.pickaudio.ui.theme.PickAudioTheme
import com.pickaudio.ui.theme.pickAudioColorScheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as PickAudioApplication
    private lateinit var saved: Map<String, String>
    @Before fun saveSettings() = runBlocking { saved = app.userPreferences.exportSettings() }
    @After fun restoreSettings() = runBlocking { app.userPreferences.restoreSettings(saved) }

    @Test fun colorChipsApplyImmediatelyInBothModesAndPersistInBackup() {
        var currentPrimary = Color.Unspecified
        compose.setContent {
            val mode by app.userPreferences.themeMode.collectAsState(ThemeMode.SYSTEM)
            val color by app.userPreferences.themeColor.collectAsState(ThemeColor.BLUE)
            PickAudioTheme(mode, color) {
                val primary = MaterialTheme.colorScheme.primary
                SideEffect { currentPrimary = primary }
                SettingsScreen(app.userPreferences, app.backupManager, onNavigateToSourceManager = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("settings_category_APPEARANCE").performClick()
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.onNodeWithTag("theme_mode_${mode.name}").performClick()
            for (color in ThemeColor.entries) {
                compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("theme_color_${color.name}"))
                compose.onNodeWithTag("theme_color_${color.name}").performClick()
                val expected = pickAudioColorScheme(color, mode == ThemeMode.DARK).primary
                compose.waitUntil(5000) { currentPrimary == expected }
                compose.onNodeWithTag("theme_color_${color.name}").assertIsSelected()
                assertEquals(color, runBlocking { UserPreferences(context).themeColor.first() })
                assertEquals(color.name, runBlocking { app.userPreferences.exportSettings()["themeColor"] })
            }
        }
    }

    @Test fun restoredColorsHandleLegacyAndInvalidSettingsWithoutResettingChoices() = runBlocking {
        val prefs = app.userPreferences
        prefs.setThemeColor(ThemeColor.PURPLE)
        val backup = prefs.exportSettings()
        prefs.setThemeColor(ThemeColor.AMBER)
        prefs.restoreSettings(backup)
        assertEquals(ThemeColor.PURPLE, prefs.themeColor.first())
        prefs.restoreSettings(mapOf("theme" to "DARK"))
        assertEquals(ThemeColor.PURPLE, prefs.themeColor.first())
        assertEquals(ThemeMode.DARK, prefs.themeMode.first())
        prefs.restoreSettings(mapOf("themeColor" to "unknown-future-color"))
        assertEquals(ThemeColor.PURPLE, prefs.themeColor.first())
    }
}
