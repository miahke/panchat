package com.bitchat.android.net

import com.bitchat.android.net.importprofile.QwdttSubscriptionLoader
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.whenever

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
class QwdttSubscriptionLoaderTest {
    private val payload =
        """{"profiles":[{"peer":"192.0.2.1:56001","password":"synthetic-password"}]}"""

    @Test
    fun `offline import never asks for a network client`() = runTest {
        val loader = QwdttSubscriptionLoader { error("Offline import attempted network") }
        assertEquals(1, loader.load(payload).size)
    }

    @Test
    fun `subscription cannot bootstrap through direct connection`() = runTest {
        var requested = false
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    requested = true
                    error("No request permitted")
                }
                .build()
        val loader = QwdttSubscriptionLoader {
            OkHttpProvider.RoutedClient(client, OkHttpProvider.Route.DIRECT)
        }
        try {
            loader.load("https://subscription.example.invalid/profile")
            fail("Direct request permitted")
        } catch (_: IllegalStateException) {}
        assertFalse(requested)
    }

    @Test
    fun `protected response is parsed and redirects are not followed`() = runTest {
        var status = 200
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("Synthetic")
                        .body(payload.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        val loader = QwdttSubscriptionLoader {
            OkHttpProvider.RoutedClient(client, OkHttpProvider.Route.TOR)
        }
        assertEquals(1, loader.load("https://subscription.example.invalid/profile").size)
        status = 302
        try {
            loader.load("https://subscription.example.invalid/profile")
            fail("Redirect accepted")
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun `cancelling an HTTPS import cancels its in-flight HTTP call`() = runTest {
        val started = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val call = org.mockito.kotlin.mock<okhttp3.Call>()
        org.mockito.kotlin.whenever(call.execute()).thenAnswer {
            started.countDown()
            check(cancelled.await(3, java.util.concurrent.TimeUnit.SECONDS))
            throw java.io.IOException("Synthetic cancellation")
        }
        org.mockito.kotlin
            .doAnswer {
                cancelled.countDown()
                null
            }
            .whenever(call)
            .cancel()
        val loader =
            QwdttSubscriptionLoader(
                client = { OkHttpProvider.RoutedClient(OkHttpClient(), OkHttpProvider.Route.TOR) },
                createCall = { _, _ -> call },
            )
        val operation = launch { loader.readSource("https://subscription.example.invalid/profile") }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
        operation.cancel()
        operation.join()
        org.mockito.kotlin.verify(call).cancel()
    }
}
