package com.bitchat.android.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.net.route.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NetworkLifecycleTest {
    @Test fun `disconnect cancels startup without changing saved protected route`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secrets = NetworkSecretStore(context.getSharedPreferences(UUID.randomUUID().toString(), 0)) { key }
        val store = NetworkProfileStore(context.getSharedPreferences(UUID.randomUUID().toString(), 0), secrets)
        store.save(NetworkProfile(id = "vk", route = NetworkRoute.VK))
        val entered = CompletableDeferred<Unit>()
        val backend = object : NetworkBackend {
            override val state = MutableStateFlow(BackendState.STARTING)
            override suspend fun start(profile: NetworkProfile): SocksEndpoint { entered.complete(Unit); awaitCancellation() }
            override suspend fun stop() {}
        }
        val manager = NetworkRouteManager({ backend })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = NetworkLifecycleController(store, manager, scope)
        try {
            controller.connect("vk")
            withTimeout(2000) { entered.await() }
            withTimeout(2000) { controller.disconnect() }
            assertEquals("vk", store.selectedId.value)
            assertEquals(NetworkRoute.VK, manager.state.value.route)
            assertEquals(BackendState.STOPPED, manager.state.value.state)
            assertNull(manager.state.value.endpoint)
        } finally { scope.cancel() }
    }
}
