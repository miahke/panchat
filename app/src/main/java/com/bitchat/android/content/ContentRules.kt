// Adapted from Nymchat ContentFilter.keywordHit/baseNym, AGPL-3.0.
// Based on Nymchat by 21 Million LLC. See third_party/nym/NOTICE.
package com.bitchat.android.content

import java.util.Locale

data class ContentRules private constructor(val keywords: List<String>) {
    fun matches(text: String, nickname: String): Boolean {
        if (keywords.isEmpty()) return false
        val body = text.lowercase(Locale.ROOT)
        val name = nickname.trim().replace(SUFFIX, "").trim().lowercase(Locale.ROOT)
        return keywords.any { body.contains(it) || name.contains(it) }
    }

    companion object {
        private val SUFFIX = Regex("#[0-9a-f]{4}$", RegexOption.IGNORE_CASE)
        const val MAX_RULES = 100
        const val MAX_LENGTH = 128
        val EMPTY = ContentRules(emptyList())

        fun parse(raw: String): ContentRules {
            require(raw.length <= 32_768) { "Too many filter characters" }
            val words =
                raw.lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { it.lowercase(Locale.ROOT) }
                    .distinct()
                    .toList()
            require(words.size <= MAX_RULES) { "Too many filters" }
            require(words.all { it.length <= MAX_LENGTH }) { "Filter is too long" }
            return ContentRules(words)
        }
    }
}
