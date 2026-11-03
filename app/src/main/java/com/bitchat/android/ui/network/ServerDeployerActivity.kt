package com.bitchat.android.ui.network

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.net.route.NetworkRuntime
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServerDeployerActivity : ComponentActivity() {
    private var exporting by mutableStateOf(false)
    private var selecting by mutableStateOf(false)
    private var exportJob: Job? = null
    private var message by mutableStateOf<String?>(null)
    private val destination =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri
            ->
            selecting = false
            if (uri == null) {
                exporting = false
                message = "Сохранение отменено."
            } else
                exportJob = lifecycleScope.launch {
                    exporting = true
                    message = null
                    try {
                        withContext(Dispatchers.IO) {
                            val expected =
                                assets
                                    .open("network/panchan-deployer.sha256")
                                    .bufferedReader()
                                    .use { it.readText().trim() }
                            assets.open("network/panchan-deployer.zip").use { input ->
                                checkNotNull(contentResolver.openOutputStream(uri, "wt")).use {
                                    output ->
                                    DeployerPackage.copyVerified(input, output, expected)
                                }
                            }
                        }
                        message =
                            "Комплект сохранён. Перенесите его на компьютер и запустите мастер."
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            runCatching { contentResolver.delete(uri, null, null) }
                        }
                        message = "Сохранение отменено."
                        throw cancelled
                    } catch (_: Exception) {
                        withContext(Dispatchers.IO) {
                            runCatching { contentResolver.delete(uri, null, null) }
                        }
                        message =
                            "Не удалось сохранить комплект. Выберите другое место и повторите."
                    } finally {
                        exporting = false
                    }
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val epoch = NetworkRuntime.wipeEpoch.value
            NetworkRuntime.wipeEpoch.first { it != epoch }
            finish()
        }
        setContent {
            BitchatTheme {
                BackHandler { if (!exporting) finish() }
                ServerDeployerScreen(
                    exporting,
                    message,
                    {
                        if (!exporting) {
                            exporting = true
                            selecting = true
                            message = null
                            try {
                                destination.launch("panchat-deployer.zip")
                            } catch (_: Exception) {
                                exporting = false
                                selecting = false
                                message =
                                    "Окно сохранения недоступно. Проверьте системное приложение «Файлы»."
                            }
                        }
                    },
                    { finish() },
                    { exportJob?.cancel() },
                    canCancel = !selecting,
                    automatic = {
                        startActivity(
                            android.content.Intent(this, ServerPreparationActivity::class.java)
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun ServerDeployerScreen(
    busy: Boolean,
    message: String?,
    export: () -> Unit,
    back: () -> Unit,
    cancel: () -> Unit = {},
    canCancel: Boolean = true,
    automatic: () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = back, enabled = !busy) { Text("Назад") }
            Text("Мой сервер", style = MaterialTheme.typography.headlineMedium)
            Text("Подготовка VPS", style = MaterialTheme.typography.titleLarge)
            Text(
                "В комплекте — мастер для компьютера. Он проверяет Debian, передаёт ядро и запускает qWDTT WG/Raw с закрытой панелью подписок."
            )
            Text(
                "Подписки qWDTT имеют отдельные ключи, сроки и лимиты устройств. Доступ можно отозвать в панели подписок.",
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            Text("1. Сохраните комплект и перенесите его на компьютер.")
            Text("2. Распакуйте ZIP. В папке комплекта запустите:")
            SelectionContainer {
                Text("python3 deploy/wizard.py", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "3. Укажите свой VPS. Мастер покажет план перед изменениями. Данные сервера в приложение зашивать не нужно."
            )
            Button(
                onClick = automatic,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("Настроить VPS из приложения")
            }
            Text("Или сохраните комплект для запуска с компьютера:")
            Button(
                onClick = export,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(
                    if (busy) if (canCancel) "Сохраняем комплект…" else "Выберите место сохранения…"
                    else "Сохранить комплект деплоера"
                )
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (canCancel) TextButton(onClick = cancel) { Text("Отменить сохранение") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
