package com.bitchat.android.net

import com.bitchat.android.net.route.*
import okhttp3.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, synthetic-content test through an owner-authorized test proxy. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[29],application=android.app.Application::class)
class QwdttSocksEgressLiveTest {
    @After fun restore() {OkHttpProvider.configureRouteProvider(null);OkHttpProvider.reset()}
    @Test fun `app HTTP and Nostr WebSocket reach their peers through authenticated qwdtt`() {
        val port=System.getenv("QWDTT_LIVE_PROXY_PORT")?.toIntOrNull()
        assumeTrue(port!=null)
        val endpoint=SocksEndpoint(port!!,checkNotNull(System.getenv("QWDTT_LIVE_PROXY_USER")),checkNotNull(System.getenv("QWDTT_LIVE_PROXY_PASSWORD")))
        OkHttpProvider.configureRouteProvider {RouteSnapshot(generation=73,route=NetworkRoute.VK,state=BackendState.READY,endpoint=endpoint)}
        val response=OkHttpProvider.httpClient().newCall(Request.Builder().url("https://example.com/").head().build()).execute()
        response.use {assertEquals(200,it.code)}
        val done=CountDownLatch(1);val opened=AtomicBoolean(false)
        val socket=OkHttpProvider.webSocketClient().newWebSocket(Request.Builder().url("wss://relay.damus.io").build(),object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){opened.set(true);webSocket.close(1000,"Synthetic connectivity test");done.countDown()}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){done.countDown()}
        })
        try {assertTrue("WebSocket probe timed out",done.await(30,TimeUnit.SECONDS));assertTrue("Nostr WebSocket did not open through SOCKS",opened.get())}finally {socket.cancel()}
    }
}
