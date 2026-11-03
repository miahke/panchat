package com.bitchat.android.ui.network

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.net.deploy.*
import com.bitchat.android.net.route.NetworkRuntime
import com.bitchat.android.ui.theme.BitchatTheme
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ServerPreparationActivity : ComponentActivity() {
    private var host by mutableStateOf("")
    private var port by mutableStateOf("22")
    private var pin by mutableStateOf("")
    private var password by mutableStateOf("")
    private var username by mutableStateOf("root")
    private var keyMode by mutableStateOf(false)
    private var privateKey by mutableStateOf<ByteArray?>(null)
    private var passphrase by mutableStateOf("")

    private fun clearSecrets() {
        password = ""
        passphrase = ""
        privateKey?.fill(0)
        privateKey = null
    }

    private val chooseKey =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null && !busy) {
                busy = true
                val ticket = ++generation
                work = lifecycleScope.launch {
                    var loaded: ByteArray? = null
                    try {
                        withContext(Dispatchers.IO) {
                            loaded =
                                contentResolver.openInputStream(uri)?.use {
                                    readPreparationPrivateKey(it)
                                }
                        }
                        ensureActive()
                        privateKey?.fill(0)
                        privateKey = checkNotNull(loaded)
                        loaded = null
                        message = "SSH-ключ выбран. Он будет удалён из памяти после операции."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        message =
                            "Не удалось прочитать ключ. Нужен приватный SSH-ключ размером до 256 КБ."
                    } finally {
                        loaded?.fill(0)
                        if (ticket == generation) busy = false
                    }
                }
            }
        }
    private var verified by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var work: Job? = null
    private var generation = 0L
    private var managing by mutableStateOf(false)
    private var accesses by mutableStateOf<List<QwdttAccess>>(emptyList())
    private var adminRequests: kotlinx.coroutines.channels.Channel<QwdttAdminRequest>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val epoch = NetworkRuntime.wipeEpoch.value
            NetworkRuntime.wipeEpoch.first { it != epoch }
            work?.cancel()
            clearSecrets()
            finish()
        }
        setContent {
            BitchatTheme {
                BackHandler { if (busy) cancel() else finish() }
                if (managing) {
                    QwdttAdminScreen(
                        accesses,
                        busy,
                        message,
                        host,
                        { request ->
                            if (!busy && adminRequests?.trySend(request)?.isSuccess == true)
                                busy = true
                        },
                        { cancel() },
                    )
                } else
                    ServerPreparationScreen(
                        host,
                        port,
                        pin,
                        password,
                        verified,
                        busy,
                        message,
                        hostChanged = {
                            host = it
                            pin = ""
                            verified = false
                        },
                        portChanged = {
                            port = it
                            pin = ""
                            verified = false
                        },
                        pinChanged = {
                            pin = it
                            verified = false
                        },
                        passwordChanged = { password = it },
                        verifiedChanged = { verified = it },
                        probe = { begin(false) },
                        deploy = { begin(true) },
                        manage = { begin(true, true) },
                        cancel = { cancel() },
                        back = { finish() },
                        username = username,
                        usernameChanged = {
                            username = it
                            verified = false
                        },
                        keyMode = keyMode,
                        keyModeChanged = {
                            keyMode = it
                            clearSecrets()
                        },
                        keySelected = privateKey != null,
                        chooseKey = { chooseKey.launch(arrayOf("*/*")) },
                        passphrase = passphrase,
                        passphraseChanged = { passphrase = it },
                        computerKit = {
                            startActivity(
                                android.content.Intent(this, ServerDeployerActivity::class.java)
                            )
                        },
                    )
            }
        }
    }

    private fun cancel() {
        generation++
        adminRequests?.close()
        adminRequests = null
        managing = false
        accesses = emptyList()
        work?.cancel()
        clearSecrets()
        busy = false
        message =
            "Операция отменена. Уже выполненные шаги подготовки могут сохраниться; мастер можно запустить повторно."
    }

    private fun begin(deploy: Boolean, manage: Boolean = false) {
        if (busy) return
        val target =
            try {
                PreparationTarget(
                    host.trim(),
                    port.toInt(),
                    if (deploy) pin.trim() else "",
                    username.trim(),
                )
            } catch (_: Exception) {
                message = "Проверьте адрес VPS, SSH-порт и SHA256-отпечаток."
                return
            }
        if (
            deploy &&
                (!verified ||
                    target.fingerprint.isEmpty() ||
                    (if (keyMode) privateKey == null else password.isEmpty()))
        ) {
            message = "Сверьте отпечаток и укажите пароль или приватный SSH-ключ."
            return
        }
        val authentication =
            try {
                if (!deploy) null
                else if (keyMode)
                    PreparationAuthentication.PrivateKey(
                        checkNotNull(privateKey).copyOf(),
                        passphrase.toByteArray(Charsets.UTF_8),
                    )
                else PreparationAuthentication.Password(password.toByteArray(Charsets.UTF_8))
            } catch (_: IllegalArgumentException) {
                clearSecrets()
                message = "Проверьте ключ и длину пароля: не более 4096 байт."
                return
            }
        if (deploy) clearSecrets()
        busy = true
        message = "Подключаемся к SSH…"
        val ticket = ++generation
        work = lifecycleScope.launch {
            try {
                if (!deploy) {
                    val value = withPreparationConnection { it.probe(target) }
                    if (ticket == generation) {
                        pin = value
                        verified = false
                        message =
                            "Сверьте этот отпечаток с панелью VPS. Получение отпечатка само по себе не подтверждает подлинность сервера."
                    }
                } else if (manage) {
                    val requests = kotlinx.coroutines.channels.Channel<QwdttAdminRequest>(1)
                    adminRequests = requests
                    withPreparationConnection { connection ->
                        connection.connect(target, checkNotNull(authentication))
                        suspend fun refresh() {
                            val result =
                                QwdttAdministration.parse(
                                    QwdttAdministration.execute(
                                        connection,
                                        QwdttAdminRequest("list"),
                                    )
                                )
                            withContext(Dispatchers.Main) {
                                if (ticket == generation) {
                                    accesses = result
                                    managing = true
                                    busy = false
                                    message = null
                                }
                            }
                        }
                        refresh()
                        for (request in requests) {
                            try {
                                QwdttAdministration.execute(connection, request)
                                refresh()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                withContext(Dispatchers.Main) {
                                    if (ticket == generation) {
                                        busy = false
                                        message =
                                            "Операция не подтверждена. Обновите список перед повтором: изменения могли сохраниться на сервере."
                                    }
                                }
                            }
                        }
                    }
                } else {
                    withPreparationConnection { connection ->
                        val directory =
                            Files.createTempDirectory(cacheDir.toPath(), "vps-kit-").toFile()
                        try {
                            val expected =
                                assets
                                    .open("network/panchan-deployer.sha256")
                                    .bufferedReader()
                                    .use { it.readText().trim() }
                            val kit =
                                assets.open("network/panchan-deployer.zip").use {
                                    PreparationKit.read(it, expected, directory)
                                }
                            connection.connect(target, checkNotNull(authentication))
                            ServerPreparation.run(connection, kit) { stage ->
                                lifecycleScope.launch {
                                    if (ticket == generation) message = stage
                                }
                            }
                        } finally {
                            directory.deleteRecursively()
                        }
                    }
                    if (ticket == generation)
                        message =
                            "qWDTT запущен и проверен. Откройте «Управление подписками» и выдайте доступ. Для нового SSH-входа снова укажите пароль или ключ."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ticket == generation)
                    message =
                        "Не удалось завершить шаг. Проверьте адрес, отпечаток, логин, пароль/ключ, права root или sudo без пароля, Debian x86_64 и свободное место. Изменившийся SSH-ключ не принимается автоматически."
            } finally {
                adminRequests?.close()
                adminRequests = null
                if (manage && ticket == generation) {
                    managing = false
                    accesses = emptyList()
                }
                authentication?.close()
                if (ticket == generation) busy = false
            }
        }
    }

    override fun onStop() {
        if (managing) cancel()
        super.onStop()
    }

    override fun onDestroy() {
        clearSecrets()
        work?.cancel()
        super.onDestroy()
    }
}

@Composable
fun ServerPreparationScreen(
    host: String,
    port: String,
    pin: String,
    password: String,
    verified: Boolean,
    busy: Boolean,
    message: String?,
    hostChanged: (String) -> Unit,
    portChanged: (String) -> Unit,
    pinChanged: (String) -> Unit,
    passwordChanged: (String) -> Unit,
    verifiedChanged: (Boolean) -> Unit,
    probe: () -> Unit,
    deploy: () -> Unit,
    cancel: () -> Unit,
    back: () -> Unit,
    computerKit: () -> Unit = {},
    manage: () -> Unit = {},
    username: String = "root",
    usernameChanged: (String) -> Unit = {},
    keyMode: Boolean = false,
    keyModeChanged: (Boolean) -> Unit = {},
    keySelected: Boolean = false,
    chooseKey: () -> Unit = {},
    passphrase: String = "",
    passphraseChanged: (String) -> Unit = {},
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = back, enabled = !busy) { Text("Назад") }
            Text("Сервер и подписки qWDTT", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Debian x86_64 · root или sudo без пароля. Приложение само передаст файлы, установит службу и проверит результат."
            )
            OutlinedTextField(
                host,
                hostChanged,
                label = { Text("IPv4 или имя сервера") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                username,
                usernameChanged,
                label = { Text("SSH-логин") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                port,
                portChanged,
                label = { Text("SSH-порт") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = probe,
                enabled = !busy && host.isNotBlank() && port.toIntOrNull() in 1..65535,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Получить SSH-отпечаток")
            }
            OutlinedTextField(
                pin,
                pinChanged,
                label = { Text("SSH-отпечаток SHA256") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                Checkbox(verified, verifiedChanged, enabled = !busy && pin.isNotBlank())
                Text("Я сверил отпечаток с панелью VPS", Modifier.padding(top = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !keyMode,
                    onClick = { keyModeChanged(false) },
                    enabled = !busy,
                    label = { Text("Пароль") },
                )
                FilterChip(
                    selected = keyMode,
                    onClick = { keyModeChanged(true) },
                    enabled = !busy,
                    label = { Text("SSH-ключ") },
                )
            }
            if (keyMode) {
                OutlinedButton(
                    onClick = chooseKey,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (keySelected) "Заменить выбранный ключ" else "Выбрать приватный ключ")
                }
                OutlinedTextField(
                    passphrase,
                    passphraseChanged,
                    label = { Text("Пароль ключа — если задан") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else
                OutlinedTextField(
                    password,
                    passwordChanged,
                    label = { Text("Пароль SSH") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            Text(
                "План: проверка системы → передача ядер → установка локальной службы → проверка готовности. SSH — прямое административное подключение к указанному VPS. Пароль, ключ и passphrase не сохраняются.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Установка запускает WG и Raw на UDP 56000/56002/56003. Панель доступна только через SSH.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = deploy,
                enabled =
                    !busy &&
                        verified &&
                        (if (keyMode) keySelected else password.isNotEmpty()) &&
                        username.isNotBlank() &&
                        pin.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("Установить и запустить qWDTT")
            }
            OutlinedButton(
                onClick = manage,
                enabled =
                    !busy &&
                        verified &&
                        pin.isNotBlank() &&
                        (if (keyMode) keySelected else password.isNotEmpty()),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("Управление подписками")
            }
            OutlinedButton(
                onClick = computerKit,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Комплект для компьютера")
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = cancel) { Text("Отменить") }
            }
            message?.let { Text(it) }
        }
    }
}
