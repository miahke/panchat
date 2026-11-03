package com.bitchat.android.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PanchatAppearanceTest {
    @Test
    fun `accent choice survives initialization and invalid values fall back`() {
        val context = RuntimeEnvironment.getApplication()
        ThemePreferenceManager.setAccent(context, ThemeAccent.Purple)
        ThemePreferenceManager.init(context)
        assertEquals(ThemeAccent.Purple, ThemePreferenceManager.accentFlow.value)
        context
            .getSharedPreferences("bitchat_settings", 0)
            .edit()
            .putString("theme_accent", "unknown")
            .commit()
        ThemePreferenceManager.init(context)
        assertEquals(ThemeAccent.Green, ThemePreferenceManager.accentFlow.value)
    }

    @Test
    fun `non green accents differ and button labels have readable contrast`() {
        for (dark in listOf(true, false)) for (accent in
            ThemeAccent.entries.filter { it != ThemeAccent.Green }) {
            val scheme = panchatColorScheme(dark, accent)
            assertNotEquals(panchatColorScheme(dark, ThemeAccent.Green).primary, scheme.primary)
            val a = scheme.primary.luminance()
            val b = scheme.onPrimary.luminance()
            assertTrue((maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05) >= 4.5)
        }
    }
}
