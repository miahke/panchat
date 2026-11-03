package com.bitchat.android.ui.network

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.net.route.*
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Structured history only: no raw upstream output or credentials. */
class NetworkDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val epoch = NetworkRuntime.wipeEpoch.value
            NetworkRuntime.wipeEpoch.first { it != epoch }
            finish()
        }
        val route =
            NetworkRoute.entries.firstOrNull { it.name == intent.getStringExtra("route") }
                ?: NetworkRoute.TOR
        setContent {
            BitchatTheme {
                val all by NetworkDiagnostics.log.events.collectAsState()
                val events = all.filter { it.route == route }
                val current = events.lastOrNull()
                val runtime by NetworkRuntime.status.collectAsState()
                val active =
                    runtime.route == route &&
                        runtime.state in
                            listOf(
                                BackendState.STARTING,
                                BackendState.READY,
                                BackendState.RECONNECTING,
                            )
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.systemBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (route == NetworkRoute.VK) {
                            QwdttStatus(
                                if (runtime.route == route) runtime
                                else RouteSnapshot(route = route),
                                events,
                            )
                        } else {
                            Text(
                                when (route) {
                                    NetworkRoute.TOR -> "Tor: подключение"
                                    NetworkRoute.VK -> "qWDTT: подключение"
                                    NetworkRoute.CUPS -> "Транспорт удалён"
                                    else -> "Подключение"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                when {
                                    active &&
                                        runtime.state == BackendState.READY &&
                                        route == NetworkRoute.TOR -> "Сеть Tor готова к передаче"
                                    active && runtime.state == BackendState.READY ->
                                        "Локальный прокси запущен"
                                    active ||
                                        (runtime.route == route &&
                                            runtime.state == BackendState.ERROR) ->
                                        current?.stage?.label ?: "Подключение ещё не запускалось"
                                    else -> "Маршрут неактивен"
                                }
                            )
                            current?.let {
                                Text(
                                    "Последнее событие: " + it.stage.label,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (runtime.state != BackendState.READY)
                                diagnosticHint(events)?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                }
                            events
                                .lastOrNull {
                                    it.stage in setOf(NetworkStage.MODE_DTLS, NetworkStage.MODE_RTP)
                                }
                                ?.let {
                                    if (active)
                                        Text(
                                            it.stage.label,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                }
                            current
                                ?.progress
                                ?.takeIf { active }
                                ?.let { percent ->
                                    LinearProgressIndicator(
                                        progress = { percent / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(percent.toString() + "% · оценка загрузки сети Arti")
                                }
                        }
                        Text(
                            "Журнал текущего процесса",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            events.forEach { event ->
                                Text(
                                    event.sequence.toString() +
                                        ". +" +
                                        (event.elapsedMillis / 1000).toString() +
                                        "с · " +
                                        event.stage.label +
                                        (event.progress?.let { " · " + it + "%" } ?: "")
                                )
                            }
                        }
                        Text(
                            if (route == NetworkRoute.VK)
                                "Журнал не содержит хэшей, адресов серверов и паролей."
                            else
                                "Адреса мостов и пароли в журнал не записываются. Готовность Tor не означает доступность конкретного реле.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { NetworkDiagnostics.log.clear() }) {
                            Text("Очистить журнал")
                        }
                        Button(onClick = { finish() }) { Text("Назад") }
                    }
                }
            }
        }
    }
}
