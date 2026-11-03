package com.bitchat.android.net.route

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QwdttStatistics(
    val workers: Int = 0,
    val upBytes: Long = 0,
    val downBytes: Long = 0,
    val received: Boolean = false,
)

fun parseQwdttStatistics(line: String): QwdttStatistics {
    val parts = line.split(':')
    require(
        parts.size == 4 &&
            parts[0] == "STATS" &&
            parts.drop(1).all {
                it.isNotEmpty() && it.length <= 19 && it.all { c -> c in '0'..'9' }
            }
    )
    val workers = parts[1].toIntOrNull()
    val up = parts[2].toLongOrNull()
    val down = parts[3].toLongOrNull()
    require(workers != null && workers in 0..2 && up != null && down != null)
    return QwdttStatistics(workers, up, down, true)
}

data class QwdttSessionDisplay(val mode: VkMode? = null, val dtls: Boolean? = null)

object QwdttTelemetry {
    private val mutableSession = MutableStateFlow(QwdttSessionDisplay())
    val session = mutableSession.asStateFlow()

    fun configure(profile: NetworkProfile) {
        mutableSession.value =
            QwdttSessionDisplay(profile.vkMode, profile.vkMode == VkMode.WG && !profile.noDtls)
    }

    fun phase(stage: NetworkStage) {
        when (stage) {
            NetworkStage.MODE_RTP -> mutableSession.value = mutableSession.value.copy(dtls = false)
            NetworkStage.MODE_DTLS -> mutableSession.value = mutableSession.value.copy(dtls = true)
            else -> Unit
        }
    }

    private val mutableSince = MutableStateFlow<Long?>(null)
    val connectedSince = mutableSince.asStateFlow()

    fun ready() {
        if (mutableSince.value == null) mutableSince.value = (System.nanoTime() / 1_000_000)
    }

    private val mutable = MutableStateFlow(QwdttStatistics())
    val statistics = mutable.asStateFlow()

    fun record(line: String) {
        mutable.value = parseQwdttStatistics(line)
    }

    fun reset() {
        mutable.value = QwdttStatistics()
        mutableSince.value = null
        mutableSession.value = QwdttSessionDisplay()
    }
}
