package com.bitchat.android.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.net.route.NetworkSecretStore
import com.bitchat.android.net.vk.VkSessionRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VkSessionRepositoryTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `panic clears persisted cookies without opening oauth in this process`() = kotlinx.coroutines.test.runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        try {
            val cookies = android.webkit.CookieManager.getInstance()
            cookies.setCookie("https://synthetic.example", "session=synthetic; Secure")
            assertNotNull(cookies.getCookie("https://synthetic.example"))
            com.bitchat.android.net.vk.VkAuthActivity.clearTransientStorage()
            assertNull(cookies.getCookie("https://synthetic.example"))
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `logout invalidates oauth already in progress`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = NetworkSecretStore(context.getSharedPreferences(UUID.randomUUID().toString(), 0)) { key }
        val repository = VkSessionRepository(store)
        val epoch = repository.generation
        repository.logout()
        try { repository.acceptToken("synthetic-old-token", epoch); fail("stale OAuth accepted") }
        catch (_: IllegalStateException) { }
        assertFalse(repository.loggedIn.value)
        repository.acceptToken("synthetic-new-token", repository.generation)
        assertTrue(repository.loggedIn.value)
    }
    @Test fun `hash generation posts token in body and returns call link hash`() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val store=NetworkSecretStore(context.getSharedPreferences(UUID.randomUUID().toString(),0)){key}
        val client=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            assertEquals("POST",request.method)
            assertNull(request.url.queryParameter("access_token"))
            assertTrue(request.body is okhttp3.FormBody)
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("""{"response":{"join_link":"https://vk.ru/call/join/synthetic_call_hash_123"}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo=VkSessionRepository(store,client)
        repo.acceptToken("synthetic-token",repo.generation)
        assertEquals("synthetic_call_hash_123",repo.generateCallHash())
    }
    @Test fun `logout cancels in-flight hash and rejects a stale response even if HTTP returns it`() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val store=NetworkSecretStore(context.getSharedPreferences(UUID.randomUUID().toString(),0)){key}
        val entered=java.util.concurrent.CountDownLatch(1);val released=java.util.concurrent.CountDownLatch(1)
        val call=org.mockito.kotlin.mock<okhttp3.Call>()
        org.mockito.kotlin.whenever(call.execute()).thenAnswer {
            entered.countDown();check(released.await(3,java.util.concurrent.TimeUnit.SECONDS))
            okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://api.vk.ru/method/calls.start").build()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("""{"response":{"join_link":"https://vk.ru/call/join/synthetic_call_hash_123"}}""".toResponseBody("application/json".toMediaType())).build()
        }
        val repo=VkSessionRepository(store,callFactory={_,_->call})
        repo.acceptToken("synthetic-token",repo.generation)
        val pending=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { runCatching { repo.generateCallHash() } }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))}
        repo.logout();released.countDown()
        assertTrue(pending.await().isFailure)
        org.mockito.kotlin.verify(call).cancel()
        assertFalse(repo.loggedIn.value)
    }
    @Test fun `invalid API session is cleared while rate limits preserve it`() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val store=NetworkSecretStore(context.getSharedPreferences(UUID.randomUUID().toString(),0)){key}
        var code=6
        val client=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body("""{"error":{"error_code":$code}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo=VkSessionRepository(store,client);repo.acceptToken("synthetic-token",repo.generation)
        try{repo.generateCallHash();fail("rate limit ignored")}catch(_:com.bitchat.android.net.vk.VkSessionException){}
        assertTrue(repo.loggedIn.value)
        code=5
        try{repo.generateCallHash();fail("bad session ignored")}catch(_:com.bitchat.android.net.vk.VkSessionException){}
        assertFalse(repo.loggedIn.value)
    }

}
