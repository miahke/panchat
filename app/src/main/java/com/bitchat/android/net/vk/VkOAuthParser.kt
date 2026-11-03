package com.bitchat.android.net.vk

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

object VkOAuthParser {
    fun allowedNavigation(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            listOf("vk.com", "vk.ru", "vk.id").any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    fun token(url: String, expectedState: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "oauth.vk.com" || uri.path != "/blank.html" ||
            uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
        val values = linkedMapOf<String, String>()
        for (pair in listOfNotNull(uri.rawQuery,uri.rawFragment).joinToString("&").takeIf { it.isNotEmpty() }?.split('&') ?: return null) {
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) return null
            val key = URLDecoder.decode(parts[0], "UTF-8")
            if (key in values) return null
            values[key] = URLDecoder.decode(parts[1], "UTF-8")
        }
        if (!MessageDigest.isEqual(values["state"]?.toByteArray() ?: return null, expectedState.toByteArray())) return null
        values["access_token"]?.takeIf { it.length in 1..8192 && it.none(Char::isWhitespace) }
    }.getOrNull()

    fun callHash(value: String): String {
        val text = value.trim()
        val hash = if (text.startsWith("https://")) {
            require(allowedNavigation(text)) { "Invalid VK call link" }
            val uri = URI(text)
            require(uri.host in setOf("vk.com", "vk.ru", "m.vk.com", "m.vk.ru") && uri.path.startsWith("/call/join/")) { "Invalid VK call link" }
            uri.path.removePrefix("/call/join/")
        } else text
        require(hash.matches(Regex("[A-Za-z0-9_=-]{16,4096}"))) { "Invalid VK call hash" }
        return hash
    }
}
