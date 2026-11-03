package com.bitchat.android.net.route

import java.util.UUID

@androidx.annotation.Keep
/** CUPS is retained only to recognize and erase obsolete persisted profiles. */
enum class NetworkRoute {
    DIRECT,
    TOR,
    VK,
    CUPS,
}

@androidx.annotation.Keep
enum class VkMode {
    WG,
    RAW,
}

/** Persistent configuration contains references to secrets, never their values. */
@androidx.annotation.Keep
data class NetworkProfile(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String = UUID.randomUUID().toString(),
    val name: String = "Network profile",
    val route: NetworkRoute = NetworkRoute.TOR,
    val socksPort: Int = 1080,
    val username: String = "bitchat",
    val vkMode: VkMode = VkMode.WG,
    val serverHost: String = "",
    val serverPort: Int = 56000,
    val turnHost: String = "",
    val turnPort: Int = 0,
    val turnTcp: Boolean = true,
    val noDtls: Boolean = false,
    val autoPeerMode: Boolean = true,
    val fallbackPort: Int = 56002,
    val connectTimeoutSeconds: Int = 90,
    val handshakeTimeoutSeconds: Int = 20,
    val configTimeoutSeconds: Int = 15,
    val tunnelDns: String = "",
    val tunnelMtu: Int = 0,
    val wgKeepaliveSeconds: Int = -1,
    val dns: String = "doh-cloudflare",
    val obfuscation: String = "audio",
) {
    val workerCount: Int
        get() = 2

    fun secretRef(kind: String): String = "$id:$kind"

    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid profile identifier" }
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid transport identity" }
        require(name.length in 1..100) { "Invalid profile name" }
        require(
            route != NetworkRoute.CUPS && route in NetworkRoute.entries && vkMode in VkMode.entries
        ) {
            "Invalid route"
        }
        require(socksPort in 1024..65535) { "Proxy port must be between 1024 and 65535" }
        require(username.toByteArray(Charsets.UTF_8).size in 1..255) { "Invalid proxy username" }
        require(serverPort in 1..65535 && turnPort in 0..65535) { "Invalid server port" }
        require(serverHost.length <= 253 && turnHost.length <= 253 && dns.length <= 2048) {
            "Configuration too long"
        }
        require(listOf(serverHost, turnHost, dns).none { it.any { ch -> ch.isISOControl() } }) {
            "Invalid configuration"
        }
        require(fallbackPort in 1..65535 && connectTimeoutSeconds in 15..300)
        require(handshakeTimeoutSeconds in 5..60 && configTimeoutSeconds in 5..60)
        require(wgKeepaliveSeconds in -1..120)
        require(tunnelMtu == 0 || tunnelMtu in 576..1500)
        validateTunnelDns(tunnelDns, route)
        require(obfuscation in listOf("audio", "video")) { "Invalid obfuscation mode" }
    }
}

class SocksCredentials(val username: String, val password: String) {
    fun validate() {
        require(username.toByteArray(Charsets.UTF_8).size in 1..255) { "Invalid proxy username" }
        require(password.toByteArray(Charsets.UTF_8).size in 1..255) { "Invalid proxy password" }
    }

    override fun toString(): String = "SocksCredentials([redacted])"
}

fun validateTunnelDns(value: String, route: NetworkRoute) {
    require(value.length <= 256 && value.none(Char::isISOControl))
    if (value.isEmpty() || route != NetworkRoute.VK) return
    val ips = value.split(',').also { require(it.size in 1..4) }
    ips.forEach { raw ->
        val parts = raw.trim().split('.')
        require(
            parts.size == 4 &&
                parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }
        )
        require(parts[0].toInt() in 1..223)
    }
}

fun NetworkProfile.defaultPeerPort(): Int =
    when {
        vkMode == VkMode.RAW -> 56003
        noDtls -> 56002
        else -> 56000
    }
