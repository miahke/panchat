package com.bitchat.android.ui.network

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bitchat.android.net.route.*
import java.util.Locale
import kotlinx.coroutines.delay

fun qwdttPipeline(
    runtime: RouteSnapshot,
    events: List<NetworkDiagnosticEvent>,
    mode: VkMode? = null,
    dtlsUsed: Boolean? = null,
): ConnectionPipelineState {
    val recent =
        events.drop((events.indexOfLast { it.stage == NetworkStage.STARTING }).coerceAtLeast(0))
    val raw =
        mode?.let { it == VkMode.RAW }
            ?: recent.any { it.stage in setOf(NetworkStage.RAW_HANDSHAKE, NetworkStage.RAW_READY) }
    val dtls =
        dtlsUsed
            ?: (recent.any {
                it.stage in setOf(NetworkStage.DTLS_HANDSHAKE, NetworkStage.DTLS_READY)
            } && recent.none { it.stage == NetworkStage.MODE_RTP })
    fun step(stage: NetworkStage): ConnectionStep =
        when (stage) {
            NetworkStage.VK_CREDENTIALS -> ConnectionStep.VK
            NetworkStage.TURN_ALLOCATING,
            NetworkStage.TURN_READY -> ConnectionStep.TURN
            NetworkStage.DTLS_HANDSHAKE,
            NetworkStage.DTLS_READY,
            NetworkStage.DTLS_TIMEOUT,
            NetworkStage.DTLS_ERROR -> ConnectionStep.DTLS
            NetworkStage.WIREGUARD_STARTING,
            NetworkStage.WIREGUARD_HANDSHAKE,
            NetworkStage.WIREGUARD_READY -> ConnectionStep.VPN
            NetworkStage.RAW_HANDSHAKE,
            NetworkStage.RAW_READY -> ConnectionStep.RAW
            else -> ConnectionStep.WORKERS
        }
    // Runtime readiness takes precedence over another worker's later startup event.
    val current =
        if (runtime.state == BackendState.READY) ConnectionStep.DONE
        else recent.lastOrNull()?.stage?.let(::step) ?: ConnectionStep.VK
    val visible = runtime.route == NetworkRoute.VK && runtime.state != BackendState.STOPPED
    val state =
        ConnectionPipelineState(
            current = current,
            visible = visible,
            dtlsUsed = dtls,
            transportKind = if (raw) ConnectionTransportKind.RAW else ConnectionTransportKind.VPN,
        )
    return state.copy(
        completed = state.stepsToShow().filter { it.order < current.order }.toSet(),
        failed = if (runtime.state == BackendState.ERROR) current else null,
    )
}

@Composable
fun QwdttStatus(runtime: RouteSnapshot, events: List<NetworkDiagnosticEvent>) {
    val statistics by QwdttTelemetry.statistics.collectAsState()
    val since by QwdttTelemetry.connectedSince.collectAsState()
    var now by remember { mutableLongStateOf((System.nanoTime() / 1_000_000)) }
    LaunchedEffect(runtime.state, since) {
        while (runtime.state == BackendState.READY) {
            now = (System.nanoTime() / 1_000_000)
            delay(1000)
        }
    }
    val session by QwdttTelemetry.session.collectAsState()
    val state = qwdttPipeline(runtime, events, session.mode, session.dtls)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (runtime.state) {
                        BackendState.READY -> "qWDTT · Подключено"
                        BackendState.STARTING -> "qWDTT · Подключение…"
                        BackendState.RECONNECTING -> "qWDTT · Переподключение…"
                        BackendState.ERROR -> "qWDTT · Ошибка подключения"
                        else -> "qWDTT · Отключено"
                    },
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.titleSmall,
                )
                val total =
                    (statistics.upBytes.toDouble() + statistics.downBytes.toDouble()) / 1048576.0
                Text(
                    if (statistics.received)
                        "Потоки: ${statistics.workers}/2 · Трафик: ${String.format(Locale.ROOT,"%.2f",total)} МБ"
                    else "Ожидаем статистику ядра…",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (runtime.state == BackendState.READY && since != null) {
                    val seconds = ((now - checkNotNull(since)).coerceAtLeast(0)) / 1000
                    Text(
                        "Сеанс: ${seconds/3600}:${(seconds/60%60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        ConnectionPipelineCard(
            state,
            isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
        )
    }
}
