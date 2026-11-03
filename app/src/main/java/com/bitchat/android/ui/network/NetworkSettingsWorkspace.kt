package com.bitchat.android.ui.network

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bitchat.android.net.importprofile.ImportedConnection
import com.bitchat.android.net.route.*
import com.bitchat.android.onboarding.NetworkSetupRole

class NetworkSettingsActions(
    val back: () -> Unit = {},
    val add: () -> Unit = {},
    val preferences: () -> Unit = {},
    val newProfile: (NetworkRoute) -> Unit = {},
    val edit: (NetworkProfile) -> Unit = {},
    val requestDelete: (NetworkProfile) -> Unit = {},
    val confirmDelete: () -> Unit = {},
    val dismissDelete: () -> Unit = {},
    val connect: (NetworkProfile) -> Unit = {},
    val disconnect: () -> Unit = {},
    val diagnostics: (NetworkRoute) -> Unit = {},
    val importText: (String) -> Unit = {},
    val analyze: () -> Unit = {},
    val choose: (ImportedConnection) -> Unit = {},
    val changeDraft: (ConnectionDraft) -> Unit = {},
    val secret: (String, String) -> Unit = { _, _ -> },
    val advanced: () -> Unit = {},
    val save: () -> Unit = {},
    val login: () -> Unit = {},
    val logout: () -> Unit = {},
    val hash: () -> Unit = {},
    val cancel: () -> Unit = {},
    val cancelPending: () -> Unit = {},
    val discard: () -> Unit = {},
    val keepEditing: () -> Unit = {},
    val role: (NetworkSetupRole) -> Unit = {},
    val region: () -> Unit = {},
    val mesh: () -> Unit = {},
    val deployer: () -> Unit = {},
)

/** Stateless presentation: tests exercise actual buttons with synthetic data and callbacks. */
@Composable
fun NetworkSettingsWorkspace(
    ui: NetworkSettingsState,
    actions: NetworkSettingsActions,
    modifier: Modifier = Modifier,
) {
    var manualChoice by remember { mutableStateOf(false) }
    var directChoice by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.systemBarsPadding().imePadding().padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ui.page != NetworkPage.HOME)
                    TextButton(
                        onClick = actions.back,
                        enabled = !ui.busy || ui.operation.cancellable,
                    ) {
                        Text("Назад")
                    }
                Text(
                    when (ui.page) {
                        NetworkPage.HOME -> "Подключения"
                        NetworkPage.IMPORT -> "Добавить подключение"
                        NetworkPage.EDITOR -> ui.draft?.profile?.route?.displayName().orEmpty()
                        NetworkPage.PREFERENCES -> "Параметры"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (ui.page == NetworkPage.HOME)
                    TextButton(onClick = actions.back) { Text("Готово") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("network-scroll"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!ui.loaded) {
                    ui.initializationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                        ?: Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Загружаем настройки…")
                        }
                } else {
                    if (ui.busy)
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text(ui.operation.title, Modifier.weight(1f))
                            if (ui.operation.cancellable)
                                TextButton(onClick = actions.cancel) { Text("Отменить") }
                        }
                    ui.error?.let { Feedback(it, true) }
                    ui.message?.let { Feedback(it, false) }
                    when (ui.page) {
                        NetworkPage.HOME -> {
                            ConnectionStatus(ui, actions)
                            if (ui.pendingSubscription != null) {
                                Text(
                                    "После защищённого подключения загрузим вашу подписку.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = actions.cancelPending) {
                                    Text("Отменить загрузку подписки")
                                }
                            }
                            Button(
                                onClick = actions.add,
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            ) {
                                Text("Добавить подключение")
                            }
                            SecondaryButton(
                                onClick = { actions.newProfile(NetworkRoute.TOR) },
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Настроить мосты Tor")
                            }
                            if (ui.profiles.isEmpty())
                                Text(
                                    "Есть готовый ключ? Вставьте его — параметры заполнятся автоматически. Для Tor можно указать собственные мосты.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            else
                                Text(
                                    "Сохранённые подключения",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            ui.profiles.forEach { profile ->
                                key(profile.id) {
                                    SavedConnectionSwipe(profile, ui.busy, actions.requestDelete) {
                                        OutlinedCard(
                                            Modifier.fillMaxWidth().testTag("saved-" + profile.id)
                                        ) {
                                            Column(
                                                Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    profile.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                                Text(
                                                    profileSummary(profile),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement =
                                                        Arrangement.SpaceBetween,
                                                ) {
                                                    TextButton(
                                                        onClick = { actions.edit(profile) },
                                                        enabled = !ui.busy,
                                                    ) {
                                                        Text("Изменить")
                                                    }
                                                    val active =
                                                        profile.id == ui.connection.profileId &&
                                                            ui.connection.state in
                                                                listOf(
                                                                    BackendState.READY,
                                                                    BackendState.STARTING,
                                                                    BackendState.RECONNECTING,
                                                                )
                                                    SecondaryButton(
                                                        onClick = {
                                                            if (active) actions.disconnect()
                                                            else actions.connect(profile)
                                                        },
                                                        enabled = !ui.busy,
                                                    ) {
                                                        Text(
                                                            if (active) "Отключить"
                                                            else "Подключить"
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            TextButton(
                                onClick = { manualChoice = true },
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Ввести параметры вручную")
                            }
                            if (ui.role == NetworkSetupRole.ADMIN)
                                SecondaryButton(
                                    onClick = actions.deployer,
                                    enabled = !ui.busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Мой сервер и подписки")
                                }
                            TextButton(
                                onClick = actions.preferences,
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Роль, область и Bluetooth")
                            }
                            Text(
                                "При ошибке защищённого маршрута приложение ждёт восстановления. Для смены способа подключения выберите другой профиль.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        NetworkPage.IMPORT -> {
                            Text(
                                "Вставьте подписку qWDTT или мосты Tor. Мы определим формат и заполним параметры."
                            )
                            NetworkField(
                                "Ссылка или настройки",
                                ui.importText,
                                actions.importText,
                                secret = true,
                                multiline = true,
                                enabled = !ui.busy,
                            )
                            if (ui.candidates.isEmpty())
                                Button(
                                    onClick = actions.analyze,
                                    enabled = !ui.busy && ui.importText.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (ui.importText.trim().startsWith("https://"))
                                            "Загрузить подписку"
                                        else "Распознать настройки"
                                    )
                                }
                            Text(
                                "Готовые ключи и содержимое подписок читаются без сети. Для HTTPS-подписки сначала подключите Tor или другой защищённый маршрут.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            ui.candidates.forEach { candidate ->
                                OutlinedCard(
                                    onClick = { actions.choose(candidate) },
                                    enabled = !ui.busy,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .testTag("import-choice-" + candidate.profile.id),
                                ) {
                                    Column(
                                        Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            candidate.profile.name,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            profileSummary(candidate.profile),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text("Выбрать", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            SecondaryButton(
                                onClick = { actions.newProfile(NetworkRoute.TOR) },
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Сначала настроить Tor")
                            }
                        }
                        NetworkPage.EDITOR -> ui.draft?.let { ConnectionEditor(ui, it, actions) }
                        NetworkPage.PREFERENCES -> {
                            com.bitchat.android.ui.theme.ThemeAccentPicker()
                            HorizontalDivider()
                            Text("Моя роль", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NetworkSetupRole.entries.forEach { choice ->
                                    FilterChip(
                                        selected = choice == ui.role,
                                        enabled = !ui.busy,
                                        onClick = { actions.role(choice) },
                                        label = {
                                            Text(
                                                if (choice == NetworkSetupRole.ADMIN)
                                                    "Администратор"
                                                else "Пользователь"
                                            )
                                        },
                                    )
                                }
                            }
                            Text(
                                "Администратор настраивает серверы. Пользователь получает готовый ключ или настраивает Tor. Выбор сохраняется; права на сервер выдаются отдельно."
                            )
                            HorizontalDivider()
                            Text("Область общения", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Выбор на глобусе сохраняется после перезапуска. GPS для этого не нужен."
                            )
                            SecondaryButton(
                                onClick = actions.region,
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Изменить область")
                            }
                            HorizontalDivider()
                            Text("Связь поблизости", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Bluetooth и Wi-Fi mesh настраиваются отдельно от интернет-подключения."
                            )
                            SecondaryButton(
                                onClick = actions.mesh,
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Настроить Bluetooth и Wi-Fi")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (ui.page == NetworkPage.EDITOR && ui.draft != null) {
                Button(
                    onClick = actions.save,
                    enabled = ui.loaded && !ui.busy,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .heightIn(min = 52.dp)
                            .testTag("save-connect"),
                ) {
                    Text(
                        if (ui.operation == NetworkOperation.CONNECT) "Сохраняем…" else "Подключить"
                    )
                }
            }
        }
    }
    if (manualChoice)
        AlertDialog(
            onDismissRequest = { manualChoice = false },
            title = { Text("Как подключиться?") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            manualChoice = false
                            actions.newProfile(NetworkRoute.VK)
                        }
                    ) {
                        Text("VK / qWDTT — свой сервер")
                    }
                    TextButton(
                        onClick = {
                            manualChoice = false
                            directChoice = true
                        }
                    ) {
                        Text("Напрямую к релеям")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { manualChoice = false }) { Text("Закрыть") } },
        )
    if (directChoice)
        AlertDialog(
            onDismissRequest = { directChoice = false },
            title = { Text("Прямое подключение") },
            text = {
                Text(
                    "Приложение будет обращаться к Nostr-релеям через вашу обычную сеть. Tor и транспорт через VPS не используются."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        directChoice = false
                        actions.newProfile(NetworkRoute.DIRECT)
                    }
                ) {
                    Text("Выбрать прямое подключение")
                }
            },
            dismissButton = { TextButton(onClick = { directChoice = false }) { Text("Отмена") } },
        )
    ui.pendingDeletion?.let { profile ->
        AlertDialog(
            onDismissRequest = { if (!ui.busy) actions.dismissDelete() },
            title = { Text("Удалить подключение?") },
            text = {
                Text(
                    "«${profile.name}»: параметры и ключи будут удалены. Активное подключение сначала остановится."
                )
            },
            confirmButton = {
                TextButton(onClick = actions.confirmDelete, enabled = !ui.busy) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = actions.dismissDelete, enabled = !ui.busy) { Text("Отмена") }
            },
        )
    }
    if (ui.confirmDiscard)
        AlertDialog(
            onDismissRequest = actions.keepEditing,
            title = { Text("Оставить изменения?") },
            text = { Text("Новые настройки ещё не сохранены.") },
            confirmButton = {
                TextButton(onClick = actions.keepEditing) { Text("Продолжить настройку") }
            },
            dismissButton = { TextButton(onClick = actions.discard) { Text("Не сохранять") } },
        )
}

private fun profileSummary(profile: NetworkProfile) =
    when (profile.route) {
        NetworkRoute.VK ->
            "qWDTT · ${profile.vkMode.name} · ${profile.serverHost}:${profile.serverPort}"
        NetworkRoute.CUPS -> "Удалённый транспорт"
        else -> profile.route.displayName()
    }

@Composable
private fun ConnectionStatus(ui: NetworkSettingsState, actions: NetworkSettingsActions) {
    val status = ui.connection
    if(status.route==NetworkRoute.VK) {
        val all by NetworkDiagnostics.log.events.collectAsState()
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            ui.profiles.firstOrNull { it.id==status.profileId }?.let { Text(it.name,style=MaterialTheme.typography.titleMedium) }
            QwdttStatus(status,all.filter { it.route==NetworkRoute.VK })
            if(status.profileId!=null)TextButton(onClick={actions.diagnostics(status.route)},enabled=!ui.busy){Text("Сведения о подключении")}
        }
        return
    }
    val title =
        when (status.state) {
            BackendState.READY -> "Подключение готово"
            BackendState.STARTING -> "Подключаемся…"
            BackendState.RECONNECTING -> "Восстанавливаем связь…"
            BackendState.ERROR -> "Не удалось подключиться"
            BackendState.STOPPED -> "Подключение отключено"
        }
    Card(
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (status.state == BackendState.ERROR) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
            ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val name =
                ui.profiles.firstOrNull { it.id == status.profileId }?.name
                    ?: status.route.displayName()
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (status.route == NetworkRoute.TOR && status.state == BackendState.STARTING)
                ui.torProgress?.let { percent ->
                    LinearProgressIndicator(
                        progress = { percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Загрузка Tor: $percent %")
                }
            if (status.profileId != null)
                TextButton(onClick = { actions.diagnostics(status.route) }, enabled = !ui.busy) {
                    Text("Сведения о подключении")
                }
        }
    }
}

@Composable
private fun ConnectionEditor(
    ui: NetworkSettingsState,
    draft: ConnectionDraft,
    a: NetworkSettingsActions,
) {
    val p = draft.profile
    val enabled = !ui.busy
    fun change(profile: NetworkProfile) = a.changeDraft(draft.copy(profile = profile))
    fun secret(key: String) = draft.secrets[key].orEmpty()
    fun error(key: String) = draft.errors[key]
    NetworkField(
        "Название",
        p.name,
        { change(p.copy(name = it)) },
        enabled = enabled,
        error = error("name"),
    )
    when (p.route) {
        NetworkRoute.TOR -> {
            Text("Мосты Tor", style = MaterialTheme.typography.titleMedium)
            Text(
                "Вставьте полные строки obfs4 или WebTunnel, по одной на строку. Можно оставить поле пустым для Tor без мостов."
            )
            NetworkField(
                "Строки мостов",
                secret("bridges"),
                { a.secret("bridges", it) },
                multiline = true,
                secret = true,
                enabled = enabled,
                error = error("bridges"),
            )
        }
        NetworkRoute.VK -> {
            Text("Сервер qWDTT", style = MaterialTheme.typography.titleMedium)
            if (draft.imported && !draft.advanced) {
                Text("${p.serverHost}:${draft.serverPort} · ${p.vkMode.name}")
                Text(
                    "Данные сервера заполнены из профиля. Свой VPS не нужен, если этот сервер предоставил администратор.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Нужен сервер qWDTT. Если есть подписка, её удобнее вставить через «Добавить подключение»."
                )
                NetworkField(
                    "Адрес сервера",
                    p.serverHost,
                    { change(p.copy(serverHost = it.trim())) },
                    enabled = enabled,
                    error = error("server"),
                )
                NetworkField(
                    "Порт сервера",
                    draft.serverPort,
                    { a.changeDraft(draft.copy(serverPort = it)) },
                    numeric = true,
                    enabled = enabled,
                    error = error("serverPort"),
                )
                NetworkField(
                    "Пароль сервера",
                    secret("vk-password"),
                    { a.secret("vk-password", it) },
                    secret = true,
                    enabled = enabled,
                    error = error("vk-password"),
                )
            }
            HorizontalDivider()
            Text("Звонок VK", style = MaterialTheme.typography.titleMedium)
            NetworkField(
                "Хэш или ссылка звонка",
                secret("vk-hash"),
                { a.secret("vk-hash", it) },
                secret = true,
                enabled = enabled,
                error = error("vk-hash"),
            )
            if (!ui.loggedIn)
                SecondaryButton(
                    onClick = a.login,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Войти в VK и получить хэш")
                }
            else {
                SecondaryButton(
                    onClick = a.hash,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Получить новый хэш")
                }
                TextButton(onClick = a.logout, enabled = enabled) { Text("Выйти из VK") }
            }
            Text(
                "Готовый хэш можно вставить вручную. Он не заменяет сервер qWDTT.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        NetworkRoute.CUPS -> error("Removed transport")
        NetworkRoute.DIRECT ->
            Text("Соединение с Nostr-релеями пойдёт через обычную сеть, без Tor или VPS.")
    }
    SecondaryButton(
        onClick = a.advanced,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("advanced-settings"),
    ) {
        Text(if (draft.advanced) "Скрыть дополнительные параметры" else "Дополнительно")
    }
    if (draft.advanced) {
        if (p.route == NetworkRoute.VK) {
            Text("Режим qWDTT", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VkMode.entries.forEach { mode ->
                    FilterChip(
                        selected = p.vkMode == mode,
                        onClick = {
                            a.changeDraft(draft.changeVkConnection(mode = mode))
                        },
                        enabled = enabled,
                        label = { Text(mode.name) },
                    )
                }
            }
            Text(
                "Два транспортных потока. Подходит для сообщений, голосовых и изображений.",
                style = MaterialTheme.typography.bodySmall,
            )
            NetworkField("DNS / DoH для VK", p.dns, { change(p.copy(dns = it)) }, enabled = enabled)
            NetworkField(
                "Адрес TURN — необязательно",
                p.turnHost,
                { change(p.copy(turnHost = it)) },
                enabled = enabled,
            )
            NetworkField(
                "Порт TURN — необязательно",
                draft.turnPort,
                { a.changeDraft(draft.copy(turnPort = it)) },
                numeric = true,
                enabled = enabled,
                error = error("turnPort"),
            )
            ToggleLine("TURN по TCP", p.turnTcp, enabled) { change(p.copy(turnTcp = it)) }
            if (p.vkMode == VkMode.WG) {
                Text("Соединение с VPS", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Авто", "DTLS", "RTP AEAD").forEachIndexed { index, label ->
                        val selected = if (p.noDtls) 2 else if (p.autoPeerMode) 0 else 1
                        FilterChip(
                            selected = selected == index,
                            onClick = {
                                a.changeDraft(
                                    draft.changeVkConnection(noDtls = index == 2, auto = index == 0)
                                )
                            },
                            enabled = enabled,
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "Авто использует только защищённые каналы через TURN. Порт DTLS обычно 56000, RTP AEAD — 56002.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else
                Text(
                    "Raw использует RTP AEAD и новый защищённый протокол Panchan. Для старого Raw-сервера требуется обновление; обычный порт — 56003.",
                    style = MaterialTheme.typography.bodySmall,
                )
            Text("Маскировка TURN", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("audio" to "Аудио", "video" to "Видео").forEach { (value, label) ->
                    FilterChip(
                        selected = p.obfuscation == value,
                        onClick = { change(p.copy(obfuscation = value)) },
                        enabled = enabled,
                        label = { Text(label) },
                    )
                }
            }
        }

        if (p.route == NetworkRoute.VK) {
            HorizontalDivider()
            Text("Сеть и восстановление", style = MaterialTheme.typography.titleMedium)
            NetworkField(
                "DNS внутри туннеля, IPv4 через запятую",
                p.tunnelDns,
                { change(p.copy(tunnelDns = it)) },
                enabled = enabled,
                error = error("tunnelDns"),
            )
            Text(
                "Пусто: DNS из конфигурации сервера. Эти запросы не используют bootstrap DNS для VK.",
                style = MaterialTheme.typography.bodySmall,
            )
            p.tuningFields().forEach { field ->
                NetworkField(
                    field.label,
                    draft.tuning[field.key] ?: field.value.toString(),
                    { a.changeDraft(draft.copy(tuning = draft.tuning + (field.key to it))) },
                    numeric = true,
                    enabled = enabled,
                    error = error(field.key),
                )
            }
        }
        if (p.route != NetworkRoute.DIRECT) {
            HorizontalDivider()
            Text("Локальный прокси", style = MaterialTheme.typography.titleMedium)
            Text(
                "Параметры уже созданы автоматически. Прокси доступен только на этом телефоне: 127.0.0.1.",
                style = MaterialTheme.typography.bodySmall,
            )
            NetworkField(
                "Порт SOCKS5",
                draft.proxyPort,
                { a.changeDraft(draft.copy(proxyPort = it)) },
                numeric = true,
                enabled = enabled,
                error = error("proxyPort"),
            )
            NetworkField(
                "Логин SOCKS5",
                p.username,
                { change(p.copy(username = it)) },
                enabled = enabled,
                error = error("username"),
            )
            NetworkField(
                "Пароль SOCKS5",
                secret("proxy-password"),
                { a.secret("proxy-password", it) },
                secret = true,
                enabled = enabled,
                error = error("proxy-password"),
            )
        }
    }
}

@Composable
private fun Feedback(text: String, error: Boolean) {
    Surface(
        color =
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            Modifier.padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ToggleLine(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = change, enabled = enabled)
    }
}

@Composable
private fun NetworkField(
    label: String,
    value: String,
    change: (String) -> Unit,
    secret: Boolean = false,
    multiline: Boolean = false,
    numeric: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
) {
    var visible by remember(label) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 7 else 1,
        visualTransformation =
            if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon =
            if (secret) {
                {
                    TextButton(onClick = { visible = !visible }, enabled = enabled) {
                        Text(if (visible) "Скрыть" else "Показать")
                    }
                }
            } else null,
        keyboardOptions =
            KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType =
                    if (numeric) KeyboardType.Number
                    else if (secret) KeyboardType.Password else KeyboardType.Text,
            ),
    )
}

@Composable
private fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors =
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.4f else 0.12f),
            ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedConnectionSwipe(
    profile: NetworkProfile,
    busy: Boolean,
    onDelete: (NetworkProfile) -> Unit,
    content: @Composable () -> Unit,
) {
    val state =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { target ->
                if (target != SwipeToDismissBoxValue.Settled && !busy) onDelete(profile)
                false
            }
        )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = !busy,
        enableDismissFromEndToStart = !busy,
        modifier =
            Modifier.fillMaxWidth().testTag("swipe-" + profile.id).semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction("Удалить подключение") {
                            if (!busy) onDelete(profile)
                            !busy
                        }
                    )
            },
        backgroundContent = {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.CenterEnd) {
                    Text("Удалить", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        },
    ) {
        content()
    }
}
