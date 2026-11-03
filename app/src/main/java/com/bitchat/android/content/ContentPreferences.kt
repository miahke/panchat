// SPDX-License-Identifier: AGPL-3.0-only
package com.bitchat.android.content

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ContentOptions(
    val rules: ContentRules = ContentRules.EMPTY,
    val hideImages: Boolean = false,
)

object ContentPreferences {
    private const val STORE = "content_preferences"
    private val current = MutableStateFlow(ContentOptions())
    val options = current.asStateFlow()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        current.value =
            try {
                val rules =
                    try {
                        ContentRules.parse(prefs.getString("keywords", "").orEmpty())
                    } catch (_: IllegalArgumentException) {
                        ContentRules.EMPTY
                    }
                ContentOptions(rules, prefs.getBoolean("hide_images", false))
            } catch (_: ClassCastException) {
                ContentOptions()
            }
    }

    fun save(context: Context, raw: String, hideImages: Boolean) {
        val rules = ContentRules.parse(raw)
        context.applicationContext
            .getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit()
            .putString("keywords", rules.keywords.joinToString("\n"))
            .putBoolean("hide_images", hideImages)
            .apply()
        current.value = ContentOptions(rules, hideImages)
    }

    fun clear(context: Context) {
        check(
            context.applicationContext
                .getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        )
        current.value = ContentOptions()
    }

    fun matches(text: String, nickname: String): Boolean =
        current.value.rules.matches(text, nickname)
}
