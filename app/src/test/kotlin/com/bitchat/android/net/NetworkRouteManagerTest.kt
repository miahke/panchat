package com.bitchat.android.net

import com.bitchat.android.net.route.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NetworkRouteManagerTest {


    @Test
    fun `reconnecting revokes endpoint until backend confirms recovery`(): Unit = runBlocking {
        val status = MutableStateFlow(BackendState.READY)
        val backend =
            object : NetworkBackend {
                override val state = status

                override suspend fun start(profile: NetworkProfile) =
                    SocksEndpoint(1080, "user", "password")

                override suspend fun stop() {}
            }
        val manager = NetworkRouteManager({ backend })
        manager.select(NetworkProfile(id = "test", route = NetworkRoute.VK))
        val generation = manager.state.value.generation
        status.value = BackendState.RECONNECTING
        withTimeout(1500) { manager.state.first { it.state == BackendState.RECONNECTING } }
        assertNull(manager.state.value.endpoint)
        assertEquals(NetworkRoute.VK, manager.state.value.route)
        assertTrue(manager.state.value.generation > generation)
        status.value = BackendState.READY
        withTimeout(1500) { manager.state.first { it.state == BackendState.READY } }
        assertNotNull(manager.state.value.endpoint)
        manager.stop()
    }

    @Test
    fun `backend death revokes endpoint and cached route generation`(): Unit = runBlocking {
        val backendState = MutableStateFlow(BackendState.READY)
        val backend =
            object : NetworkBackend {
                override val state = backendState

                override suspend fun start(profile: NetworkProfile) =
                    SocksEndpoint(1080, "user", "password")

                override suspend fun stop() {}
            }
        val manager = NetworkRouteManager({ backend })
        manager.select(NetworkProfile(id = "vk", route = NetworkRoute.VK))
        val generation = manager.state.value.generation
        backendState.value = BackendState.ERROR
        withTimeout(1500) { manager.state.first { it.state == BackendState.ERROR } }
        assertNull(manager.state.value.endpoint)
        assertTrue(manager.state.value.generation > generation)
        manager.stop()
    }

    @Test
    fun `stops previous backend and blocks route before starting replacement`() = runTest {
        NetworkDiagnostics.log.clear()
        val operations = mutableListOf<String>()
        lateinit var manager: NetworkRouteManager
        fun backend(name: String) =
            object : NetworkBackend {
                override val state = MutableStateFlow(BackendState.STOPPED)

                override suspend fun start(profile: NetworkProfile): SocksEndpoint {
                    assertEquals(BackendState.STARTING, manager.state.value.state)
                    assertNull(manager.state.value.endpoint)
                    operations.add("start:$name")
                    return SocksEndpoint(profile.socksPort, "user", "password")
                }

                override suspend fun stop() {
                    operations.add("stop:$name")
                }
            }
        val vk = backend("vk")
        val tor = backend("tor")
        manager = NetworkRouteManager({ route -> if (route == NetworkRoute.VK) vk else tor })
        manager.select(NetworkProfile(id = "vk", route = NetworkRoute.VK))
        val generation = manager.state.value.generation
        manager.select(NetworkProfile(id = "tor", route = NetworkRoute.TOR))
        assertEquals(listOf("start:vk", "stop:vk", "start:tor"), operations)
        assertTrue(manager.state.value.generation > generation)
        assertEquals(NetworkRoute.TOR, manager.state.value.route)
        assertEquals(
            NetworkStage.STOPPED,
            NetworkDiagnostics.log.events.value.last { it.route == NetworkRoute.VK }.stage,
        )
    }

    @Test
    fun `startup failure remains blocked and never becomes direct`() = runTest {
        val backend =
            object : NetworkBackend {
                override val state = MutableStateFlow(BackendState.STOPPED)

                override suspend fun start(profile: NetworkProfile): SocksEndpoint =
                    error("secret detail")

                override suspend fun stop() {}
            }
        val manager = NetworkRouteManager({ backend })
        manager.select(NetworkProfile(id = "vk", route = NetworkRoute.VK))
        assertEquals(BackendState.ERROR, manager.state.value.state)
        assertEquals(NetworkRoute.VK, manager.state.value.route)
        assertNull(manager.state.value.endpoint)
        assertFalse(manager.state.value.toString().contains("secret detail"))
        manager.stop()
        assertEquals(NetworkRoute.VK, manager.state.value.route)
        assertEquals(BackendState.STOPPED, manager.state.value.state)
    }
}
