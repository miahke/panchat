package com.bitchat.android.ui.network

import com.bitchat.android.net.route.*
import org.junit.Assert.*
import org.junit.Test

class NetworkTuningTest {
    @Test
    fun `standard peer ports follow mode but custom ports survive`() {
        val draft =
            ConnectionDraft(
                NetworkProfile(id = "test", route = NetworkRoute.VK, serverPort = 56000),
                emptyMap(),
            )
        assertEquals("56002", draft.changeVkConnection(noDtls = true).serverPort)
        assertEquals("56003", draft.changeVkConnection(mode = VkMode.RAW).serverPort)
        assertEquals(
            "12345",
            draft.copy(serverPort = "12345").changeVkConnection(mode = VkMode.RAW).serverPort,
        )
    }



    @Test
    fun `valid settings reach the saved profile and malformed values are rejected`() {
        val draft =
            ConnectionDraft(
                NetworkProfile(id = "test", route = NetworkRoute.VK),
                emptyMap(),
                tuning =
                    mapOf(
                        "connectTimeout" to "120",
                        "handshakeTimeout" to "10",
                        "tunnelMtu" to "1280",
                    ),
            )
        val errors = mutableMapOf<String, String>()
        val result = applyNetworkTuning(draft, errors)
        assertTrue(errors.isEmpty())
        assertEquals(120, result.connectTimeoutSeconds)
        assertEquals(10, result.handshakeTimeoutSeconds)
        assertEquals(1280, result.tunnelMtu)
        applyNetworkTuning(draft.copy(tuning = mapOf("connectTimeout" to "broken")), errors)
        assertTrue(errors.containsKey("connectTimeout"))
    }
}
