package com.bitchat.android.content

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ContentPreferencesTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @After
    fun reset() {
        ContentPreferences.clear(context)
    }

    @Test
    fun `saved rules and image choice survive reload and clear`() {
        ContentPreferences.save(context, "offer\nphrase", true)
        ContentPreferences.init(context)
        assertTrue(ContentPreferences.options.value.rules.matches("OFFER", "reader"))
        assertTrue(ContentPreferences.options.value.hideImages)
        ContentPreferences.clear(context)
        ContentPreferences.init(context)
        assertFalse(ContentPreferences.options.value.hideImages)
        assertTrue(ContentPreferences.options.value.rules.keywords.isEmpty())
    }

    @Test
    fun `failed save leaves earlier settings intact`() {
        ContentPreferences.save(context, "offer", true)
        assertThrows(IllegalArgumentException::class.java) {
            ContentPreferences.save(context, "x".repeat(129), false)
        }
        ContentPreferences.init(context)
        assertEquals(listOf("offer"), ContentPreferences.options.value.rules.keywords)
        assertTrue(ContentPreferences.options.value.hideImages)
    }

    @Test
    fun `corrupt persisted rules do not crash startup`() {
        context
            .getSharedPreferences("content_preferences", 0)
            .edit()
            .putString("keywords", "x".repeat(129))
            .commit()
        ContentPreferences.init(context)
        assertTrue(ContentPreferences.options.value.rules.keywords.isEmpty())
    }
}
