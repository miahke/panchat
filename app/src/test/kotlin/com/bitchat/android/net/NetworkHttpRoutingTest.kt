package com.bitchat.android.net

import com.bitchat.android.net.route.*
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NetworkHttpRoutingTest {
    @After fun restore() { OkHttpProvider.configureRouteProvider(null) }

    @Test fun `selected tunnel without endpoint rejects requests instead of using direct internet`() {
        OkHttpProvider.configureRouteProvider {
            RouteSnapshot(route = NetworkRoute.VK, state = BackendState.ERROR)
        }
        val failure = assertThrows(java.io.IOException::class.java) {
            OkHttpProvider.httpClient().newCall(Request.Builder().url("https://relay.example.invalid").build()).execute()
        }
        assertEquals("Selected network route is not ready", failure.message)
        assertEquals(OkHttpProvider.Route.VK, OkHttpProvider.routedHttpClient().route)
    }

    @Test fun `held client cannot connect after route generation changes`() {
        var snapshot = RouteSnapshot(generation = 1, route = NetworkRoute.DIRECT, state = BackendState.READY)
        OkHttpProvider.configureRouteProvider { snapshot }
        val old = OkHttpProvider.httpClient()
        snapshot = RouteSnapshot(generation = 2, route = NetworkRoute.VK, state = BackendState.STARTING)
        val failure = assertThrows(java.io.IOException::class.java) {
            old.newCall(Request.Builder().url("https://relay.example.invalid").build()).execute()
        }
        assertEquals("Network route changed", failure.message)
    }
}
