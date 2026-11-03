package com.bitchat.android.net.importprofile

import com.bitchat.android.net.bridge.BridgeParser
import com.bitchat.android.net.route.NetworkProfile
import com.bitchat.android.net.route.NetworkRoute
import com.google.gson.Gson

class ImportedConnection(val profile: NetworkProfile, val secrets: Map<String, String>) {
    override fun toString() = "ImportedConnection([redacted])"
}

/** Detects supported local formats without fetching URLs, opening apps or resolving hosts. */
object ConnectionImport {
    fun parse(input: String): List<ImportedConnection> {
        require(input.length in 1..131072)
        val source = input.trim()
        require(source.isNotEmpty())
        require(!source.startsWith("openflux://")) { "OpenFlux удалён. Используйте Tor или qWDTT." }
        val first =
            source
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                .orEmpty()
        if (
            first.startsWith("obfs4 ") ||
                first.startsWith("webtunnel ") ||
                first.startsWith("Bridge ") ||
                first.substringBefore(' ').let {
                    ':' in it && first.contains(' ') && !first.startsWith('{')
                }
        ) {
            val bridges = BridgeParser.parse(source)
            require(bridges.isNotEmpty())
            return listOf(
                ImportedConnection(
                    NetworkProfile(name = "Мои мосты Tor", route = NetworkRoute.TOR),
                    mapOf("bridges" to bridges.joinToString("\n") { it.toTorLine() }),
                )
            )
        }
        return QwdttSubscriptionImport.parse(source).map {
            ImportedConnection(it.profile, it.secrets)
        }
    }
}
