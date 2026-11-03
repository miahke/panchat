package com.bitchat.android.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.net.bridge.BridgeParser
import com.bitchat.android.net.bridge.BridgeValidationException
import com.bitchat.android.net.importprofile.*
import com.bitchat.android.net.route.*
import com.bitchat.android.onboarding.NetworkSetupRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** All transient secrets live in this process only, never SavedStateHandle/Bundle. */
class NetworkSettingsViewModel(private val operations: NetworkSettingsOperations) : ViewModel() {
    private val mutable = MutableStateFlow(NetworkSettingsState(role = operations.initialRole))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var automatic: Job? = null
    private var generation = 0L

    fun goImport() {
        if (!state.value.busy)
            mutable.update { it.copy(page = NetworkPage.IMPORT, error = null, message = null) }
    }

    fun openPreferences() {
        if (!state.value.busy)
            mutable.update { it.copy(page = NetworkPage.PREFERENCES, error = null, message = null) }
    }

    fun updateImportText(text: String) {
        if (state.value.busy) return
        automatic?.cancel()
        if (text.length > 131072) {
            mutable.update {
                it.copy(error = "Настройки слишком большие. Допустимо не более 128 КБ.")
            }
            return
        }
        val pasted = text.length - state.value.importText.length > 1
        mutable.update {
            it.copy(importText = text, candidates = emptyList(), error = null, message = null)
        }
        val source = text.trim()
        val local =
            source.startsWith("openflux://") ||
                source.startsWith("qwdtt:") ||
                source.startsWith('{') ||
                source.startsWith('[') ||
                source.startsWith("obfs4 ") ||
                source.startsWith("webtunnel ") ||
                source.startsWith("Bridge ") ||
                (source.length >= 24 && source.matches(Regex("[A-Za-z0-9_+/=\\r\\n -]+")))
        if (pasted && local)
            automatic = viewModelScope.launch {
                delay(250)
                startImport()
            }
    }

    fun analyzeImport() {
        automatic?.cancel()
        startImport()
    }

    private fun startImport() {
        val source = state.value.importText.trim()
        if (source.isEmpty()) {
            mutable.update {
                it.copy(error = "Вставьте подписку qWDTT или строки мостов Tor.")
            }
            return
        }
        execute(NetworkOperation.IMPORT) { ticket ->
            val result = operations.importSource(source)
            current(ticket)
            require(result.size in 1..32)
            if (result.size == 1) selectImported(result.single())
            else
                mutable.update {
                    it.copy(
                        candidates = result,
                        importText = "",
                        error = null,
                        message = "Найдено подключений: ${result.size}. Выберите нужное.",
                    )
                }
        }
    }

    fun chooseImport(value: ImportedConnection) {
        if (!state.value.busy) selectImported(value)
    }

    private fun selectImported(value: ImportedConnection) {
        value.profile.validate()
        mutable.update {
            it.copy(
                page = NetworkPage.EDITOR,
                draft = prepare(value.profile, value.secrets).copy(imported = true),
                dirty = true,
                candidates = emptyList(),
                importText = "",
                pendingSubscription = null,
                error = null,
                message = "Настройки заполнены. Проверьте их и нажмите «Подключить».",
            )
        }
    }

    fun newProfile(route: NetworkRoute) {
        if (state.value.busy) return
        val profile = NetworkProfile(name = route.displayName(), route = route)
        val pending =
            state.value.importText.trim().takeIf {
                state.value.page == NetworkPage.IMPORT &&
                    route == NetworkRoute.TOR &&
                    it.startsWith("https://")
            }
        mutable.update {
            it.copy(
                page = NetworkPage.EDITOR,
                draft = prepare(profile, emptyMap()),
                dirty = false,
                error = null,
                message = null,
                pendingSubscription = pending,
            )
        }
    }

    fun editProfile(profile: NetworkProfile) =
        execute(NetworkOperation.LOAD) { ticket ->
            val secrets = operations.readSecrets(profile)
            current(ticket)
            mutable.update {
                it.copy(
                    page = NetworkPage.EDITOR,
                    draft = prepare(profile, secrets).copy(imported = true),
                    dirty = false,
                    error = null,
                    message = null,
                )
            }
        }

    private fun prepare(profile: NetworkProfile, secrets: Map<String, String>): ConnectionDraft {
        val values = secrets.toMutableMap()
        if (values["proxy-password"].isNullOrEmpty())
            values["proxy-password"] = operations.randomPassword()
        return ConnectionDraft(profile, values.toMap())
    }

    fun updateDraft(change: (ConnectionDraft) -> ConnectionDraft) {
        if (state.value.busy) return
        mutable.update { ui ->
            ui.draft?.let {
                ui.copy(
                    draft = change(it).copy(errors = emptyMap()),
                    dirty = true,
                    error = null,
                    message = null,
                )
            } ?: ui
        }
    }

    fun updateSecret(kind: String, value: String) = updateDraft {
        it.copy(secrets = it.secrets + (kind to value))
    }

    fun toggleAdvanced() {
        mutable.update { ui ->
            ui.draft?.let { ui.copy(draft = it.copy(advanced = !it.advanced)) } ?: ui
        }
    }

    fun connectDraft() {
        val draft = state.value.draft ?: return
        if (state.value.busy) return
        val errors = linkedMapOf<String, String>()
        val p = applyNetworkTuning(draft, errors)
        if (p.name.isBlank() || p.name.length > 100)
            errors["name"] = "Введите название до 100 символов."
        val port = draft.proxyPort.toIntOrNull()
        if (p.route != NetworkRoute.DIRECT && (port == null || port !in 1024..65535))
            errors["proxyPort"] = "Порт: от 1024 до 65535."
        val serverPort = draft.serverPort.toIntOrNull()
        val turnPort = if (draft.turnPort.isBlank()) 0 else draft.turnPort.toIntOrNull()
        if (p.route != NetworkRoute.DIRECT) {
            if (p.username.toByteArray(Charsets.UTF_8).size !in 1..255)
                errors["username"] = "Логин прокси: от 1 до 255 байт."
            if (
                draft.secrets["proxy-password"].orEmpty().toByteArray(Charsets.UTF_8).size !in
                    1..255
            )
                errors["proxy-password"] = "Пароль прокси: от 1 до 255 байт."
        }
        if (p.route == NetworkRoute.VK) {
            if (
                p.serverHost.isBlank() ||
                    p.serverHost.length > 253 ||
                    p.serverHost.any(Char::isISOControl)
            )
                errors["server"] = "Нужен адрес сервера qWDTT из подписки или от администратора."
            if (serverPort == null || serverPort !in 1..65535)
                errors["serverPort"] = "Порт сервера: от 1 до 65535."
            if (turnPort == null || turnPort !in 0..65535)
                errors["turnPort"] = "Укажите корректный порт TURN."
            if (draft.secrets["vk-password"].isNullOrBlank())
                errors["vk-password"] = "Нужен пароль сервера из подписки или от администратора."
            if (draft.secrets["vk-hash"].isNullOrBlank())
                errors["vk-hash"] = "Вставьте хэш звонка или войдите в VK для его получения."
            else
                try {
                    com.bitchat.android.net.vk.VkOAuthParser.callHash(
                        draft.secrets.getValue("vk-hash")
                    )
                } catch (_: Exception) {
                    errors["vk-hash"] =
                        "Неверный хэш или ссылка. Нужен хэш звонка VK либо ссылка vk.com/call/join/…"
                }
        }

        if (p.route == NetworkRoute.TOR)
            try {
                BridgeParser.parse(draft.secrets["bridges"].orEmpty())
            } catch (failure: BridgeValidationException) {
                errors["bridges"] =
                    "Проверьте строку моста №${failure.lineNumber}. Нужна полная строка obfs4 или webtunnel."
            } catch (_: Exception) {
                errors["bridges"] = "Список мостов слишком большой или содержит неверные строки."
            }
        if (errors.isNotEmpty()) {
            val hidden =
                errors.keys.any {
                    it in
                        setOf("proxyPort", "username", "proxy-password", "turnPort", "tunnelDns") ||
                        it in draft.profile.tuningFields().map { field -> field.key }
                } ||
                    (draft.imported &&
                        errors.keys.any {
                            it in
                                setOf(
                                    "server",
                                    "serverPort",
                                    "vk-password",
                                )
                        })
            mutable.update {
                it.copy(
                    draft = draft.copy(errors = errors, advanced = draft.advanced || hidden),
                    error = "Проверьте отмеченные поля.",
                )
            }
            return
        }
        val ready =
            p.copy(
                socksPort = port ?: p.socksPort,
                serverPort = serverPort ?: p.serverPort,
                turnPort = turnPort ?: p.turnPort,
            )
        execute(NetworkOperation.CONNECT) { ticket ->
            ready.validate()
            operations.saveAndConnect(ready, draft.secrets)
            current(ticket)
            mutable.update {
                it.copy(
                    page = NetworkPage.HOME,
                    draft = null,
                    dirty = false,
                    message = "Настройки сохранены. Подключение запускается.",
                    error = null,
                )
            }
        }
    }

    fun connectSaved(profile: NetworkProfile) =
        execute(NetworkOperation.CONNECT) { ticket ->
            operations.connectSaved(profile.id)
            current(ticket)
            mutable.update { it.copy(message = "Подключение запускается.") }
        }

    fun requestDelete(profile: NetworkProfile) {
        if (!state.value.busy) mutable.update { it.copy(pendingDeletion = profile) }
    }

    fun dismissDelete() {
        mutable.update { it.copy(pendingDeletion = null) }
    }

    fun confirmDelete() {
        val profile = state.value.pendingDeletion ?: return
        execute(NetworkOperation.DELETE) { ticket ->
            operations.removeProfile(profile.id)
            current(ticket)
            mutable.update { it.copy(pendingDeletion = null, message = "Подключение удалено.") }
        }
    }

    fun disconnect() =
        execute(NetworkOperation.STOP) { ticket ->
            operations.disconnect()
            current(ticket)
            mutable.update { it.copy(message = "Интернет-маршрут отключён.") }
        }

    fun generateHash() {
        val id = state.value.draft?.profile?.id ?: return
        execute(NetworkOperation.HASH) { ticket ->
            val value = operations.generateHash()
            current(ticket)
            mutable.update { ui ->
                if (ui.draft?.profile?.id == id)
                    ui.copy(
                        draft = ui.draft.copy(secrets = ui.draft.secrets + ("vk-hash" to value)),
                        dirty = true,
                        message = "Хэш звонка получен.",
                    )
                else ui
            }
        }
    }

    fun awaitingVkLogin() {
        mutable.update { it.copy(awaitingVkLogin = true) }
    }

    fun vkLoginReturned(success: Boolean) {
        if (success) vkLoginCompleted()
        else
            mutable.update {
                it.copy(
                    awaitingVkLogin = false,
                    message = "Вход VK отменён. Можно повторить или вставить хэш вручную.",
                )
            }
    }

    fun vkLoginCompleted() {
        if (state.value.awaitingVkLogin) {
            mutable.update { it.copy(awaitingVkLogin = false) }
            generateHash()
        }
    }

    fun logout() =
        execute(NetworkOperation.LOGOUT) { ticket ->
            operations.logout()
            current(ticket)
            mutable.update { it.copy(message = "Вы вышли из VK.") }
        }

    fun changeRole(role: NetworkSetupRole) =
        execute(NetworkOperation.ROLE) { ticket ->
            operations.saveRole(role)
            current(ticket)
            mutable.update { it.copy(role = role, message = "Роль сохранена.") }
        }

    fun back() {
        val ui = state.value
        if (ui.busy) {
            if (ui.operation.cancellable) cancelOperation() else return
        }
        if (ui.page == NetworkPage.EDITOR && ui.dirty) {
            mutable.update { it.copy(confirmDiscard = true) }
            return
        }
        discardDraft()
    }

    fun keepEditing() {
        mutable.update { it.copy(confirmDiscard = false) }
    }

    fun discardDraft() {
        automatic?.cancel()
        mutable.update {
            it.copy(
                page = NetworkPage.HOME,
                draft = null,
                dirty = false,
                confirmDiscard = false,
                importText = "",
                candidates = emptyList(),
                pendingSubscription = null,
                error = null,
                message = null,
                awaitingVkLogin = false,
            )
        }
    }

    fun resumePendingSubscription(route: RouteSnapshot) {
        if (
            state.value.busy ||
                state.value.page != NetworkPage.HOME ||
                route.route != NetworkRoute.TOR ||
                route.state != BackendState.READY
        )
            return
        val source = state.value.pendingSubscription ?: return
        mutable.update {
            it.copy(
                page = NetworkPage.IMPORT,
                importText = source,
                pendingSubscription = null,
                error = null,
                message = null,
            )
        }
        analyzeImport()
    }

    fun cancelPendingSubscription() {
        mutable.update {
            it.copy(
                pendingSubscription = null,
                importText = "",
                message = "Загрузка подписки отменена.",
            )
        }
    }

    fun cancelOperation() {
        if (!state.value.operation.cancellable) return
        generation++
        job?.cancel()
        automatic?.cancel()
        mutable.update {
            it.copy(operation = NetworkOperation.NONE, message = "Действие отменено.", error = null)
        }
    }

    fun clearTransient() {
        generation++
        job?.cancel()
        automatic?.cancel()
        mutable.value = NetworkSettingsState(role = state.value.role)
    }

    private suspend fun current(ticket: Long) {
        currentCoroutineContext().ensureActive()
        if (ticket != generation) throw CancellationException()
    }

    private fun execute(operation: NetworkOperation, action: suspend (Long) -> Unit) {
        if (state.value.busy) return
        val ticket = ++generation
        mutable.update { it.copy(operation = operation, error = null, message = null) }
        job = viewModelScope.launch {
            try {
                action(ticket)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (ticket == generation)
                    mutable.update {
                        it.copy(
                            error =
                                when (operation) {
                                    NetworkOperation.IMPORT ->
                                        if (it.importText.trim().startsWith("https://"))
                                            "Не удалось загрузить подписку. Подключите Tor или другой защищённый маршрут и повторите. Также можно вставить содержимое подписки."
                                        else
                                            "Не удалось распознать настройки. Поддерживаются qwdtt://config, JSON/base64 подписка и мосты Tor."
                                    NetworkOperation.HASH ->
                                        (failure as? com.bitchat.android.net.vk.VkSessionException)
                                            ?.publicMessage
                                            ?: "VK не выдал хэш. Повторите вход или вставьте готовый хэш вручную."
                                    NetworkOperation.ROLE ->
                                        "Не удалось сохранить роль. Повторите попытку."
                                    NetworkOperation.LOAD ->
                                        "Не удалось открыть сохранённые настройки."
                                    else ->
                                        "Не удалось выполнить действие. Проверьте настройки и повторите попытку."
                                }
                        )
                    }
            } finally {
                if (ticket == generation)
                    mutable.update { it.copy(operation = NetworkOperation.NONE) }
            }
        }
    }
}
