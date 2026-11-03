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
class ChatReadingPreferencesTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @After
    fun reset() {
        ChatReadingPreferences.clear(context)
    }

    @Test
    fun `reading choices persist and clearing restores defaults`() {
        ChatReadingPreferences.save(context, 130, false)
        ChatReadingPreferences.init(context)
        assertEquals(130, ChatReadingPreferences.options.value.fontPercent)
        assertEquals("h:mm a", ChatReadingPreferences.options.value.timePattern)
        ChatReadingPreferences.clear(context)
        ChatReadingPreferences.init(context)
        assertEquals(100, ChatReadingPreferences.options.value.fontPercent)
        assertEquals("HH:mm", ChatReadingPreferences.options.value.timePattern)
    }

    @Test
    fun `invalid font sizes cannot replace saved choices`() {
        ChatReadingPreferences.save(context, 115, true)
        assertThrows(IllegalArgumentException::class.java) {
            ChatReadingPreferences.save(context, 1000, false)
        }
        assertEquals(115, ChatReadingPreferences.options.value.fontPercent)
    }
}
