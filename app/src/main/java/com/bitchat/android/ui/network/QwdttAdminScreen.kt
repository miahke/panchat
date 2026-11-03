package com.bitchat.android.ui.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bitchat.android.net.deploy.*
import java.text.DateFormat
import java.util.Date

@Composable
fun QwdttAdminScreen(
    entries: List<QwdttAccess>,
    busy: Boolean,
    message: String?,
    host: String,
    request: (QwdttAdminRequest) -> Unit,
    close: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("30") }
    var devices by remember { mutableStateOf("1") }
    var selected by remember { mutableStateOf<QwdttAccess?>(null) }
    var action by remember { mutableStateOf("") }
    var shared by remember { mutableStateOf<QwdttAccess?>(null) }
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.systemBarsPadding()
                .imePadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Подписки qWDTT", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Доступ к вашему серверу. Выдайте отдельную подписку каждому человеку: её можно отключить, не затрагивая остальных."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ creating = true }, enabled = !busy) { Text("Выдать доступ") }
                OutlinedButton({ request(QwdttAdminRequest("list")) }, enabled = !busy) {
                    Text("Обновить")
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it) }
            if (entries.isEmpty() && !busy)
                Text("Подписок пока нет. Для выдачи нужен хэш звонка VK.")
            entries.forEach { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            entry.label.ifBlank { "Доступ" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (entry.revoked) "Отключён"
                            else if (
                                entry.expiresAt > 0 &&
                                    entry.expiresAt * 1000 < System.currentTimeMillis()
                            )
                                "Срок истёк"
                            else "Активен"
                        )
                        if (entry.expiresAt > 0)
                            Text(
                                "До " +
                                    DateFormat.getDateInstance()
                                        .format(Date(entry.expiresAt * 1000))
                            )
                        Text(
                            "Устройств: ${entry.devices} · Получено: ${entry.received / 1024} КБ · Отправлено: ${entry.sent / 1024} КБ"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                { shared = entry },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                enabled = !busy && entry.hash.isNotBlank(),
                            ) {
                                Text("Ключ настройки")
                            }
                            TextButton(
                                {
                                    selected = entry
                                    action = "update"
                                    label = entry.label
                                    hash = entry.hash
                                    devices = entry.devices.toString()
                                    days = "30"
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                enabled = !busy,
                            ) {
                                Text("Изменить")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                {
                                    selected = entry
                                    action = if (entry.revoked) "activate" else "deactivate"
                                },
                                enabled = !busy,
                            ) {
                                Text(if (entry.revoked) "Включить" else "Отключить")
                            }
                            TextButton(
                                {
                                    selected = entry
                                    action = "delete"
                                },
                                enabled = !busy,
                            ) {
                                Text("Удалить")
                            }
                        }
                    }
                }
            }
            Text(
                "SSH-реквизиты не сохраняются. Закрытие панели завершает сеанс. Ключ настройки содержит пароль: передавайте его только получателю.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(close, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Закрыть панель")
            }
        }
    }
    if (creating)
        AlertDialog(
            onDismissRequest = {
                creating = false
                hash = ""
            },
            title = { Text("Новая подписка") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        label,
                        { label = it.filterNot(Char::isISOControl).take(90) },
                        label = { Text("Имя получателя") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        hash,
                        { hash = it.take(4096) },
                        label = { Text("Хэш звонка VK") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        days,
                        { days = it },
                        label = { Text("Срок, дней: 1–365") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        devices,
                        { devices = it },
                        label = { Text("Лимит устройств: 1–32") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        request(
                            QwdttAdminRequest(
                                "create",
                                mapOf(
                                    "label" to label,
                                    "vk_hash" to hash.trim(),
                                    "days" to days,
                                    "max_devices" to devices,
                                ),
                            )
                        )
                        creating = false
                        hash = ""
                    },
                    enabled =
                        hash.isNotBlank() &&
                            hash.none(Char::isISOControl) &&
                            (days.toIntOrNull() ?: 0) in 1..365 &&
                            (devices.toIntOrNull() ?: 0) in 1..32,
                ) {
                    Text("Выдать")
                }
            },
            dismissButton = {
                TextButton({
                    creating = false
                    hash = ""
                }) {
                    Text("Отмена")
                }
            },
        )
    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Text(
                    when (action) {
                        "delete" -> "Удалить подписку?"
                        "deactivate" -> "Отключить доступ?"
                        "activate" -> "Включить доступ?"
                        else -> "Параметры подписки"
                    }
                )
            },
            text = {
                if (action == "update")
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            label,
                            { label = it.filterNot(Char::isISOControl).take(90) },
                            label = { Text("Имя получателя") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            hash,
                            { hash = it.take(4096) },
                            label = { Text("Хэш звонка VK") },
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        OutlinedTextField(
                            days,
                            { days = it },
                            label = { Text("Дней с сегодняшнего дня: 1–365") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            devices,
                            { devices = it },
                            label = { Text("Лимит устройств: 1–32") },
                            singleLine = true,
                        )
                    }
                else
                    Text(
                        if (action == "delete")
                            "Удаление необратимо. Подключённые устройства потеряют доступ."
                        else "Изменение применяется на сервере к выбранной подписке."
                    )
            },
            confirmButton = {
                TextButton(
                    {
                        request(
                            QwdttAdminRequest(
                                action,
                                buildMap {
                                    put("password", entry.password)
                                    if (action == "update") {
                                        put("days", days)
                                        put("label", label)
                                        put("vk_hash", hash.trim())
                                        put("max_devices", devices)
                                    }
                                },
                            )
                        )
                        selected = null
                    },
                    enabled =
                        action != "update" ||
                            (hash.isNotBlank() &&
                                hash.none(Char::isISOControl) &&
                                (days.toIntOrNull() ?: 0) in 1..365 &&
                                (devices.toIntOrNull() ?: 0) in 1..32),
                ) {
                    Text("Подтвердить")
                }
            },
            dismissButton = { TextButton({ selected = null }) { Text("Отмена") } },
        )
    }
    shared?.let { entry ->
        AlertDialog(
            onDismissRequest = { shared = null },
            title = { Text("Ключ настройки готов") },
            text = {
                Text(
                    "Скопируйте ключ и отправьте получателю защищённым способом. Он вставит его в «Добавить подключение». Пароль SSH в ключ не входит."
                )
            },
            confirmButton = {
                TextButton({
                    val clip = ClipData.newPlainText("qWDTT", entry.subscription(host))
                    if (android.os.Build.VERSION.SDK_INT >= 24)
                        clip.description.extras =
                            android.os.PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(clip)
                    shared = null
                }) {
                    Text("Скопировать ключ")
                }
            },
            dismissButton = { TextButton({ shared = null }) { Text("Закрыть") } },
        )
    }
}
