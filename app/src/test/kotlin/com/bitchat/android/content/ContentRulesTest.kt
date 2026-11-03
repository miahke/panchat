package com.bitchat.android.content

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ContentRulesTest {
    @Test
    fun `matches literal phrases and nicknames without identifier suffix`() {
        val rules = ContentRules.parse("продам\nLOUD OFFER\n.*\n \nпродам")
        assertEquals(3, rules.keywords.size)
        assertTrue(rules.matches("Это ПРОДАМ вещь", "reader"))
        assertTrue(rules.matches("hello", "LOUD OFFER#abcd"))
        assertTrue(rules.matches("literal .* text", "reader"))
        assertFalse(rules.matches("ordinary", "reader"))
        assertFalse(ContentRules.parse("abcd").matches("hello", "reader#abcd"))
        assertTrue(ContentRules.parse("abcd").matches("hello", "reader#abcdx"))
    }

    @Test
    fun `normalization is independent of device locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(ContentRules.parse("invite").matches("INVITE", "reader"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `invalid oversized rules reject rather than silently truncate`() {
        assertThrows(IllegalArgumentException::class.java) { ContentRules.parse("x".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) {
            ContentRules.parse((1..101).joinToString("\n") { "word$it" })
        }
        assertFalse(ContentRules.parse(" \n\n ").matches("anything", "reader"))
    }
}
