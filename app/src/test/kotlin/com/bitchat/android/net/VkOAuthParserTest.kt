package com.bitchat.android.net

import com.bitchat.android.net.vk.VkOAuthParser
import org.junit.Assert.*
import org.junit.Test

class VkOAuthParserTest {
    @Test fun `callback query is accepted only with exact redirect and nonce`() {
        assertEquals("synthetic-token",VkOAuthParser.token("https://oauth.vk.com/blank.html?access_token=synthetic-token&state=nonce","nonce"))
        assertNull(VkOAuthParser.token("https://oauth.vk.com/blank.html?access_token=synthetic-token&state=nonce#state=other","nonce"))
    }
    @Test fun `only exact oauth redirect with matching state supplies token`() {
        assertEquals("synthetic-token", VkOAuthParser.token("https://oauth.vk.com/blank.html#access_token=synthetic-token&state=nonce", "nonce"))
        for (url in listOf(
            "https://oauth.vk.com.evil.invalid/blank.html#access_token=synthetic-token&state=nonce",
            "https://oauth.vk.com/blank.html#access_token=synthetic-token&state=wrong",
            "http://oauth.vk.com/blank.html#access_token=synthetic-token&state=nonce",
            "https://oauth.vk.com/other#access_token=synthetic-token&state=nonce",
            "https://oauth.vk.com/blank.html#access_token=synthetic-token&state=nonce&state=nonce",
        )) assertNull(VkOAuthParser.token(url, "nonce"))
    }

    @Test fun `webview navigation rejects lookalike hosts and non https schemes`() {
        assertTrue(VkOAuthParser.allowedNavigation("https://login.vk.com/path"))
        assertFalse(VkOAuthParser.allowedNavigation("https://evilvk.com/path"))
        assertFalse(VkOAuthParser.allowedNavigation("file:///synthetic-file"))
        assertFalse(VkOAuthParser.allowedNavigation("javascript:alert(1)"))
    }

    @Test fun `imports only vk call links or standalone hash`() {
        assertEquals("synthetic_call_hash", VkOAuthParser.callHash("https://vk.ru/call/join/synthetic_call_hash"))
        assertEquals("synthetic_call_hash", VkOAuthParser.callHash("synthetic_call_hash"))
        assertThrows(IllegalArgumentException::class.java) { VkOAuthParser.callHash("https://evil.invalid/call/join/synthetic_call_hash") }
    }
}
