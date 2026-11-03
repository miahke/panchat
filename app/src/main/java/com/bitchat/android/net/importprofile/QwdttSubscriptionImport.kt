package com.bitchat.android.net.importprofile

import com.bitchat.android.net.route.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

class ImportedVkProfile(val profile: NetworkProfile, val secrets: Map<String, String>) {
    override fun toString() = "ImportedVkProfile([redacted])"
}

/** Compatible with upstream SubscriptionImport fields; no URL fetch or DNS during parsing. */
object QwdttSubscriptionImport {
    fun parse(source: String): List<ImportedVkProfile> {
        require(source.length in 1..131072) { "Subscription is too large" }
        var text = source.trim()
        if (!(text.startsWith("{") || text.startsWith("[") || text.startsWith("qwdtt:"))) {
            val packed = text.filterNot(Char::isWhitespace)
            val decoded = runCatching {
                Base64.getDecoder().decode(packed)
            }
                .getOrElse { Base64.getUrlDecoder().decode(packed) }
            require(decoded.size <= 65536)
            text = decoded.toString(Charsets.UTF_8).trim()
        }
        require(text.length <= 65536)
        val objects: List<JsonObject> =
            if (text.startsWith("qwdtt:")) listOf(uriProfile(text))
            else {
                val root = JsonParser.parseString(text)
                if (root.isJsonObject && root.asJsonObject.has("peer")) listOf(root.asJsonObject)
                else {
                    val array =
                        if (root.isJsonArray) root.asJsonArray
                        else
                            root.asJsonObject.let {
                                it.getAsJsonArray("profiles") ?: it.getAsJsonArray("servers")
                            }
                    require(array != null && array.size() in 1..32) {
                        "Subscription contains no supported profiles"
                    }
                    array.map { it.asJsonObject }
                }
            }
        val profiles = objects.flatMap { profile(it) }
        require(profiles.size in 1..32) { "Too many imported profiles" }
        return profiles
    }

    private fun uriProfile(text: String): JsonObject {
        val uri =
            URI(
                if (text.startsWith("qwdtt:config"))
                    text.replaceFirst("qwdtt:config", "qwdtt://config")
                else text
            )
        require(
            uri.scheme == "qwdtt" &&
                uri.host == "config" &&
                uri.path.isNullOrEmpty() &&
                uri.fragment == null &&
                uri.userInfo == null
        )
        val values = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).forEach {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            require(key !in values) { "Duplicate subscription field" }
            values[key] = URLDecoder.decode(parts[1], "UTF-8")
        }
        return JsonObject().apply { values.forEach { (key, value) -> addProperty(key, value) } }
    }

    private fun profile(json: JsonObject): List<ImportedVkProfile> {
        fun value(primary: String, alternate: String? = null): String =
            (json.get(primary) ?: alternate?.let(json::get))
                ?.takeUnless { it.isJsonNull }
                ?.asString
                .orEmpty()
        val peer = value("peer")
        require(peer.isNotBlank() && peer.length <= 300)
        val hostInput =
            if (peer.count { it == ':' } > 1 && !peer.startsWith('[')) "[$peer]" else peer
        val address = URI("tcp://" + hostInput)
        require(
            !address.host.isNullOrBlank() &&
                (address.port == -1 || address.port in 1..65535) &&
                address.userInfo == null &&
                address.path.isNullOrEmpty() &&
                address.query == null &&
                address.fragment == null
        ) {
            "Invalid subscription server"
        }
        // Upstream's `port` is the local listener, not the VPS destination.
        val proxyPort = value("port", "listenPort").let { if (it.isBlank()) 1080 else it.toInt() }
        require(proxyPort in 1024..65535) { "Invalid local proxy port" }

        val password = value("password", "pass")
        require(password.isNotEmpty() && password.length <= 4096)
        val hashes =
            value("hashes", "vkHashes")
                .split(Regex("[,\\r\\n]+"))
                .map(String::trim)
                .filter(String::isNotEmpty)
                .ifEmpty { listOf("") }
        require(
            hashes.size <= 32 && hashes.all { it.length <= 4096 && it.none(Char::isISOControl) }
        )
        val mode =
            when (value("mode").lowercase()) {
                "raw" -> VkMode.RAW
                "",
                "wg",
                "wireguard" -> VkMode.WG
                else -> throw IllegalArgumentException("Unsupported qWDTT mode")
            }
        val name = value("name").ifBlank { "qWDTT" }.take(90)
        return hashes.mapIndexed { index, hash ->
            val profile =
                NetworkProfile(
                    name = name + if (hashes.size > 1) " #" + (index + 1) else "",
                    route = NetworkRoute.VK,
                    serverHost = address.host.removeSurrounding("[", "]"),
                    serverPort = if (address.port != -1) address.port else if(mode==VkMode.RAW)56003 else 56000,
                    socksPort = proxyPort,
                    vkMode = mode,
                )
            profile.validate()
            ImportedVkProfile(
                profile,
                buildMap {
                    put("vk-password", password)
                    if (hash.isNotEmpty()) put("vk-hash", hash)
                },
            )
        }
    }
}
