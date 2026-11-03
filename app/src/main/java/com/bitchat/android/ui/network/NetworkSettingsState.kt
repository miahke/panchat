package com.bitchat.android.ui.network

import com.bitchat.android.net.importprofile.ImportedConnection
import com.bitchat.android.net.route.*
import com.bitchat.android.onboarding.NetworkSetupRole

enum class NetworkPage {
    HOME,
    IMPORT,
    EDITOR,
    PREFERENCES,
}

enum class NetworkOperation(val title: String, val cancellable: Boolean = false) {
    NONE(""),
    IMPORT("Читаем настройки…", true),
    LOAD("Открываем профиль…", true),
    CONNECT("Сохраняем настройки…"),
    STOP("Отключаемся…"),
    DELETE("Удаляем подключение…"),
    HASH("Получаем хэш VK…", true),
    LOGOUT("Выходим из VK…"),
    ROLE("Сохраняем выбор…"),
}

data class ConnectionDraft(
    val profile: NetworkProfile,
    val secrets: Map<String, String>,
    val proxyPort: String = profile.socksPort.toString(),
    val serverPort: String = profile.serverPort.toString(),
    val turnPort: String = profile.turnPort.takeIf { it != 0 }?.toString().orEmpty(),
    val advanced: Boolean = false,
    val imported: Boolean = false,
    val tuning: Map<String,String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
) {
    override fun toString() = "ConnectionDraft([redacted])"
}

data class NetworkSettingsState(
    val page: NetworkPage = NetworkPage.HOME,
    val operation: NetworkOperation = NetworkOperation.NONE,
    val error: String? = null,
    val message: String? = null,
    val importText: String = "",
    val candidates: List<ImportedConnection> = emptyList(),
    val pendingSubscription: String? = null,
    val draft: ConnectionDraft? = null,
    val dirty: Boolean = false,
    val confirmDiscard: Boolean = false,
    val pendingDeletion: NetworkProfile? = null,
    val awaitingVkLogin: Boolean = false,
    val role: NetworkSetupRole = NetworkSetupRole.USER,
    val profiles: List<NetworkProfile> = emptyList(),
    val connection: RouteSnapshot = RouteSnapshot(),
    val loaded: Boolean = true,
    val initializationError: String? = null,
    val loggedIn: Boolean = false,
    val torProgress: Int? = null,
) {
    val busy: Boolean
        get() = operation != NetworkOperation.NONE

    override fun toString() = "NetworkSettingsState([redacted])"
}

fun NetworkRoute.displayName() =
    when (this) {
        NetworkRoute.TOR -> "Tor"
        NetworkRoute.VK -> "VK / qWDTT"
        NetworkRoute.CUPS -> "Удалённый транспорт"
        NetworkRoute.DIRECT -> "Прямое подключение"
    }
