package com.bitchat.android.net

import com.bitchat.android.net.route.*
import org.junit.Assert.*
import org.junit.Test

class NetworkDiagnosticsTest {


    @Test
    fun `history is bounded and repeated progress is coalesced`() {
        val log = NetworkDiagnosticLog(4)
        repeat(20) { log.record(NetworkRoute.TOR, NetworkStage.BOOTSTRAPPING, 12) }
        assertEquals(1, log.events.value.size)
        for (n in 13..20) log.record(NetworkRoute.TOR, NetworkStage.BOOTSTRAPPING, n)
        assertEquals(4, log.events.value.size)
        assertEquals(20, log.events.value.last().progress)
    }

    @Test
    fun `only valid native progress is accepted`() {
        val log = NetworkDiagnosticLog()
        assertThrows(IllegalArgumentException::class.java) {
            log.record(NetworkRoute.TOR, NetworkStage.BOOTSTRAPPING, 101)
        }
        log.record(NetworkRoute.VK, NetworkStage.STARTING)
        assertNull(log.events.value.single().progress)
        log.clear()
        assertTrue(log.events.value.isEmpty())
    }
}
