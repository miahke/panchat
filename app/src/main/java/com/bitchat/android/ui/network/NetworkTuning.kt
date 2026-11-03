package com.bitchat.android.ui.network

import com.bitchat.android.net.route.*

data class NetworkTuningField(
    val key: String,
    val label: String,
    val value: Int,
    val range: IntRange,
)

fun NetworkProfile.tuningFields(): List<NetworkTuningField> = buildList {
    if (route != NetworkRoute.VK) return@buildList
    add(
        NetworkTuningField(
            "connectTimeout",
            "Общий таймаут подключения, с",
            connectTimeoutSeconds,
            15..300,
        )
    )
    add(NetworkTuningField("tunnelMtu", "MTU туннеля, 0 = автоматически", tunnelMtu, 0..1500))
    if (route == NetworkRoute.VK) {
        add(
            NetworkTuningField(
                "handshakeTimeout",
                "Таймаут рукопожатия, с",
                handshakeTimeoutSeconds,
                5..60,
            )
        )
        add(
            NetworkTuningField(
                "configTimeout",
                "Ожидание конфигурации VPS, с",
                configTimeoutSeconds,
                5..60,
            )
        )
        add(
            NetworkTuningField(
                "wgKeepalive",
                "Keepalive WG, с; −1 = сервер",
                wgKeepaliveSeconds,
                -1..120,
            )
        )
        if (autoPeerMode && !noDtls && vkMode == VkMode.WG)
            add(
                NetworkTuningField("fallbackPort", "Порт RTP AEAD для Авто", fallbackPort, 1..65535)
            )
    }
}

fun applyNetworkTuning(draft: ConnectionDraft, errors: MutableMap<String, String>): NetworkProfile {
    val p = draft.profile
    val values =
        p.tuningFields().associate { field ->
            val value =
                draft.tuning[field.key]?.toIntOrNull()
                    ?: if (field.key in draft.tuning) null else field.value
            if (value == null || value !in field.range)
                errors[field.key] = "Допустимо ${field.range.first}…${field.range.last}."
            field.key to (value?.takeIf { it in field.range } ?: field.value)
        }
    fun get(key: String, fallback: Int) = values[key] ?: fallback
    val configured =
        p.copy(
            connectTimeoutSeconds = get("connectTimeout", p.connectTimeoutSeconds),
            tunnelMtu = get("tunnelMtu", p.tunnelMtu),
            handshakeTimeoutSeconds = get("handshakeTimeout", p.handshakeTimeoutSeconds),
            configTimeoutSeconds = get("configTimeout", p.configTimeoutSeconds),
            wgKeepaliveSeconds = get("wgKeepalive", p.wgKeepaliveSeconds),
            fallbackPort = get("fallbackPort", p.fallbackPort),
        )
    if (configured.tunnelMtu != 0 && configured.tunnelMtu !in 576..1500)
        errors["tunnelMtu"] = "Укажите 0 или MTU 576…1500."

    try {
        validateTunnelDns(p.tunnelDns, p.route)
    } catch (_: Exception) {
        errors["tunnelDns"] = "Укажите IPv4 через запятую или оставьте пустым."
    }
    return configured
}

fun ConnectionDraft.changeVkConnection(
    mode: VkMode = profile.vkMode,
    noDtls: Boolean = profile.noDtls,
    auto: Boolean = profile.autoPeerMode,
): ConnectionDraft {
    val next = profile.copy(vkMode = mode, noDtls = noDtls, autoPeerMode = auto)
    val standard = serverPort.toIntOrNull() == profile.defaultPeerPort() || serverPort == "56001"
    return copy(
        profile = next,
        serverPort = if (standard) next.defaultPeerPort().toString() else serverPort,
    )
}
