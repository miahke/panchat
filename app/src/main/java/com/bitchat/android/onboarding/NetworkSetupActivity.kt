package com.bitchat.android.onboarding

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.MainActivity
import com.bitchat.android.geohash.*
import com.bitchat.android.net.route.NetworkRuntime
import com.bitchat.android.ui.GeohashPickerActivity
import com.bitchat.android.ui.network.NetworkSettingsActivity
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkSetupActivity : ComponentActivity() {
    private lateinit var preferences: NetworkSetupPreferences
    private var step by mutableIntStateOf(0)
    private var role by mutableStateOf(NetworkSetupRole.USER)
    private var error by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private val picker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val region = result.data?.getStringExtra(GeohashPickerActivity.EXTRA_RESULT_GEOHASH)
            if (result.resultCode == Activity.RESULT_OK && region != null)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { preferences.setRegion(region) }
                        step = 1
                    } catch (_: Exception) {
                        error = "Не удалось сохранить выбранную область"
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
        preferences = NetworkSetupPreferences.from(this)
        role = preferences.role
        if (preferences.completed && !intent.getBooleanExtra("edit", false)) {
            openChat()
            return
        }
        step = preferences.phase
        setContent {
            BitchatTheme {
                val connection by NetworkRuntime.status.collectAsState()
                LaunchedEffect(Unit) {
                    if (step == 0 && savedInstanceState == null) openRegionPicker()
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.systemBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Настройка panchat", style = MaterialTheme.typography.headlineMedium)
                        AnimatedContent(targetState = step, label = "setup-step") { page ->
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                when (page) {
                                    0 -> {
                                        Text(
                                            "Выберите область на земном шаре. GPS и определение вашего местоположения не используются."
                                        )
                                        Button(onClick = { openRegionPicker() }) {
                                            Text("Открыть земной шар")
                                        }
                                    }
                                    1 -> {
                                        Text("Кто вы?", style = MaterialTheme.typography.titleLarge)
                                        NetworkSetupRole.entries.forEach { choice ->
                                            FilterChip(
                                                selected = role == choice,
                                                enabled = !saving,
                                                onClick = {
                                                    saving = true
                                                    lifecycleScope.launch {
                                                        try {
                                                            withContext(Dispatchers.IO) {
                                                                preferences.setRole(choice)
                                                            }
                                                            role = choice
                                                        } catch (_: Exception) {
                                                            error = "Не удалось сохранить роль"
                                                        } finally {
                                                            saving = false
                                                        }
                                                    }
                                                },
                                                label = {
                                                    Text(
                                                        if (choice == NetworkSetupRole.ADMIN)
                                                            "Администратор"
                                                        else "Пользователь"
                                                    )
                                                },
                                            )
                                        }
                                        Text(
                                            "Администратор умеет и будет настраивать серверы. Пользователь настраивает мосты Tor или получает настройки сервера от администратора. Роль можно изменить позднее; права на сервер выдаются отдельно."
                                        )
                                        Button(onClick = { moveToStep(2) }, enabled = !saving) {
                                            Text("Продолжить")
                                        }
                                        TextButton(onClick = { moveToStep(0) }, enabled = !saving) {
                                            Text("Изменить область")
                                        }
                                    }
                                    else -> {
                                        val ready =
                                            connection.state ==
                                                com.bitchat.android.net.route.BackendState.READY
                                        Text(
                                            if (ready) "Подключение настроено"
                                            else "Как подключиться?",
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        if (ready) {
                                            Text(
                                                "Выбранное подключение готово. Можно начать общение или изменить настройки."
                                            )
                                            Button(onClick = ::finishSetup, enabled = !saving) {
                                                Text("Начать общение")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    startActivity(
                                                        Intent(
                                                            this@NetworkSetupActivity,
                                                            NetworkSettingsActivity::class.java,
                                                        )
                                                    )
                                                },
                                                enabled = !saving,
                                            ) {
                                                Text("Изменить подключение")
                                            }
                                        } else {
                                            Text(
                                                "Есть готовый ключ или подписка? Вставьте их — параметры заполнятся автоматически. Для Tor можно указать собственные мосты."
                                            )
                                            Button(
                                                onClick = {
                                                    startActivity(
                                                        Intent(
                                                                this@NetworkSetupActivity,
                                                                NetworkSettingsActivity::class.java,
                                                            )
                                                            .putExtra("setup_action", "import")
                                                    )
                                                },
                                                enabled = !saving,
                                            ) {
                                                Text("Добавить подключение")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    startActivity(
                                                        Intent(
                                                                this@NetworkSetupActivity,
                                                                NetworkSettingsActivity::class.java,
                                                            )
                                                            .putExtra("setup_action", "tor")
                                                    )
                                                },
                                                enabled = !saving,
                                            ) {
                                                Text("Настроить мосты Tor")
                                            }
                                            if (role == NetworkSetupRole.ADMIN)
                                                OutlinedButton(
                                                    onClick = {
                                                        startActivity(
                                                            Intent(
                                                                this@NetworkSetupActivity,
                                                                com.bitchat.android.ui.network
                                                                        .ServerPreparationActivity::class
                                                                    .java,
                                                            )
                                                        )
                                                    },
                                                    enabled = !saving,
                                                ) {
                                                    Text("Подготовить мой сервер")
                                                }
                                            Text(
                                                "Если пропустить этот шаг, интернет-подключение можно настроить позже."
                                            )
                                            TextButton(onClick = ::finishSetup, enabled = !saving) {
                                                Text("Пропустить")
                                            }
                                        }
                                        TextButton(onClick = { moveToStep(1) }, enabled = !saving) {
                                            Text("Назад")
                                        }
                                    }
                                }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    private fun moveToStep(value: Int) {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { preferences.setPhase(value) }
                step = value
            } catch (_: Exception) {
                error = "Не удалось сохранить шаг настройки. Повторите попытку."
            } finally {
                saving = false
            }
        }
    }

    private fun openRegionPicker() {
        picker.launch(
            Intent(this, GeohashPickerActivity::class.java)
                .putExtra(GeohashPickerActivity.EXTRA_MANUAL_ONLY, true)
                .putExtra(GeohashPickerActivity.EXTRA_INITIAL_GEOHASH, preferences.region ?: "s")
        )
    }

    private fun finishSetup() {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try {
                val region = checkNotNull(preferences.region)
                withContext(Dispatchers.IO) { preferences.complete() }
                val level =
                    when (region.length) {
                        in 1..2 -> GeohashChannelLevel.REGION
                        in 3..4 -> GeohashChannelLevel.PROVINCE
                        5 -> GeohashChannelLevel.CITY
                        6 -> GeohashChannelLevel.NEIGHBORHOOD
                        7 -> GeohashChannelLevel.BLOCK
                        else -> GeohashChannelLevel.BUILDING
                    }
                LocationChannelManager.getInstance(applicationContext)
                    .selectManual(GeohashChannel(level, region))
                runCatching { NetworkRuntime.resumeSelected() }
                openChat()
            } catch (_: Exception) {
                error = "Не удалось завершить настройку. Проверьте параметры подключения."
            } finally {
                saving = false
            }
        }
    }

    private fun openChat() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
