package com.bitchat.android.ui.network

import com.bitchat.android.net.route.*
import org.junit.Assert.*
import org.junit.Test

class PanchatPipelineTest {
    @Test
    fun `ready tunnel stays complete when second worker reports startup`() {
        val log = NetworkDiagnosticLog()
        log.record(NetworkRoute.VK, NetworkStage.STARTING)
        log.record(NetworkRoute.VK, NetworkStage.WIREGUARD_READY)
        log.record(NetworkRoute.VK, NetworkStage.TURN_ALLOCATING)
        val state =
            qwdttPipeline(
                RouteSnapshot(route = NetworkRoute.VK, state = BackendState.READY),
                log.events.value,
            )
        assertEquals(ConnectionStep.DONE, state.current)
        assertTrue(state.visible)
        assertFalse(state.stepsToShow().contains(ConnectionStep.DNS))
    }

    @Test
    fun `raw pipeline does not claim a DTLS handshake`() {
        val log = NetworkDiagnosticLog()
        log.record(NetworkRoute.VK, NetworkStage.RAW_READY)
        val state =
            qwdttPipeline(
                RouteSnapshot(route = NetworkRoute.VK, state = BackendState.READY),
                log.events.value,
            )
        assertEquals(ConnectionTransportKind.RAW, state.transportKind)
        assertFalse(state.stepsToShow().contains(ConnectionStep.DTLS))
    }

    @Test
    fun `clearing logs does not relabel the active Raw session as WG`() {
        val state =
            qwdttPipeline(
                RouteSnapshot(route = NetworkRoute.VK, state = BackendState.READY),
                emptyList(),
                VkMode.RAW,
                false,
            )
        assertEquals(ConnectionTransportKind.RAW, state.transportKind)
        assertEquals(ConnectionStep.DONE, state.current)
    }
}
