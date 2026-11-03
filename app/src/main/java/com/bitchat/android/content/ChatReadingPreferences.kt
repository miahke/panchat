// SPDX-License-Identifier: AGPL-3.0-only
package com.bitchat.android.content

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatReadingOptions(val fontPercent: Int = 100, val use24Hour: Boolean = true) {
    val timePattern: String
        get() = if (use24Hour) "HH:mm" else "h:mm a"
}

object ChatReadingPreferences {
    private const val STORE = "chat_reading_preferences"
    private val current = MutableStateFlow(ChatReadingOptions())
    val options = current.asStateFlow()
    val fontChoices = listOf(85, 100, 115, 130, 150)

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        current.value =
            try {
                val size = prefs.getInt("font_percent", 100).takeIf { it in fontChoices } ?: 100
                ChatReadingOptions(size, prefs.getBoolean("use_24_hour", true))
            } catch (_: ClassCastException) {
                ChatReadingOptions()
            }
    }

    fun save(context: Context, fontPercent: Int, use24Hour: Boolean) {
        require(fontPercent in fontChoices)
        context.applicationContext
            .getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit()
            .putInt("font_percent", fontPercent)
            .putBoolean("use_24_hour", use24Hour)
            .apply()
        current.value = ChatReadingOptions(fontPercent, use24Hour)
    }

    fun clear(context: Context) {
        check(
            context.applicationContext
                .getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        )
        current.value = ChatReadingOptions()
    }
}
