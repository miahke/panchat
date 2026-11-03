package com.bitchat.android.net

import com.bitchat.android.net.route.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TorNetworkBackendTest {
    @Test fun `native bootstrap failure is reported immediately and cleaned up`() = runTest {
        var stopped = false
        val engine = object : TorEngine {
            override fun configure(directory: String, bridges: String, program: String, username: String, password: String) = Unit
            override fun listen(port: Int) = Unit
            override fun isReady() = false
            override fun bootstrapProgress() = -2
            override fun stop() { stopped = true }
        }
        NetworkDiagnostics.log.clear()
        val backend = TorNetworkBackend(File("synthetic-state"), File("/synthetic/transport"), { ref ->
            if (ref.endsWith(":bridges")) "" else "synthetic-password"
        }, engine)
        try { backend.start(NetworkProfile(id = "synthetic-tor")); fail("bootstrap failure accepted") }
        catch (_: IllegalStateException) { }
        assertTrue(stopped)
        assertEquals(BackendState.ERROR, backend.state.value)
        assertEquals(NetworkStage.BOOTSTRAP_ERROR, NetworkDiagnostics.log.events.value.last().stage)
    }

    @Test fun `passes user bridge lines and proxy authentication to native engine`() = runTest {
        val commands = mutableListOf<String>()
        val engine = object : TorEngine {
            override fun configure(directory: String, bridges: String, program: String, username: String, password: String) {
                assertTrue(bridges.startsWith("webtunnel 192.0.2.1:443"))
                assertEquals("bitchat", username)
                assertEquals("synthetic-password", password)
                commands.add("configure")
            }
            override fun listen(port: Int) { assertEquals(1080, port); commands.add("listen") }
            override fun isReady() = true
            override fun stop() { commands.add("stop") }
        }
        val backend = TorNetworkBackend(File("synthetic-state"), File("/synthetic/libbitchat_pt.so"), { ref ->
            if (ref.endsWith(":bridges")) "webtunnel 192.0.2.1:443 0123456789ABCDEF0123456789ABCDEF01234567 url=https://bridge.example.invalid/path ver=0.0.3"
            else "synthetic-password"
        }, engine)
        val endpoint = backend.start(NetworkProfile(id = "tor", route = NetworkRoute.TOR))
        assertEquals("synthetic-password", endpoint.password)
        assertEquals(BackendState.READY, backend.state.value)
        backend.stop()
        assertEquals(listOf("configure", "listen", "stop"), commands)
        assertEquals(BackendState.STOPPED, backend.state.value)
    }
}
