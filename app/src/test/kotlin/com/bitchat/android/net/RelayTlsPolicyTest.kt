package com.bitchat.android.net

import com.bitchat.android.net.route.*
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.net.*
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RelayTlsPolicyTest {
    private class Relay : AutoCloseable {
        val keys = KeyStore.getInstance("PKCS12").apply {
            RelayTlsPolicyTest::class.java.getResourceAsStream("/network/synthetic-relay.p12").use { load(it, "synthetic-test-password".toCharArray()) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "synthetic-test-password".toCharArray()) }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val trust = trustManagers.trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, trustManagers.trustManagers, null) }
        val server = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val proxy = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val workers = Executors.newCachedThreadPool()
        val sockets = CopyOnWriteArrayList<Socket>()
        val observed = ByteArrayOutputStream()
        @Volatile var host = ""
        @Volatile var received = ""
        @Volatile var serverError: Exception? = null
        @Volatile var proxyError: Exception? = null
        init {
            workers.submit {
                try {
                    val socket = (server.accept() as SSLSocket).also { sockets.add(it); it.soTimeout = 3000 }
                    socket.use {
                        it.startHandshake()
                        val reader = it.inputStream.bufferedReader()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length); var at = 0
                        while (at < length) { val n = reader.read(body, at, length - at); if (n < 0) break; at += n }
                        received = String(body)
                        it.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    }
                } catch (e: Exception) { serverError = e }
            }
            workers.submit {
                try {
                    val client = proxy.accept().also { sockets.add(it); it.soTimeout = 3000 }
                    val input = DataInputStream(client.inputStream)
                    check(input.readUnsignedByte() == 5)
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    client.outputStream.write(byteArrayOf(5, 2))
                    check(input.readUnsignedByte() == 1)
                    input.readFully(ByteArray(input.readUnsignedByte())); input.readFully(ByteArray(input.readUnsignedByte()))
                    client.outputStream.write(byteArrayOf(1, 0))
                    input.readFully(ByteArray(3)); check(input.readUnsignedByte() == 3)
                    val name = ByteArray(input.readUnsignedByte()); input.readFully(name); host = String(name)
                    input.readUnsignedShort()
                    client.outputStream.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
                    val target = Socket(InetAddress.getLoopbackAddress(), server.localPort).also { sockets.add(it) }
                    workers.submit { runCatching { target.inputStream.copyTo(client.outputStream) }; runCatching { client.close() } }
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = client.inputStream.read(buffer); if (n < 0) break
                        observed.write(buffer, 0, n); target.outputStream.write(buffer, 0, n)
                    }
                } catch (e: Exception) { proxyError = e }
            }
        }
        fun client(trusted: Boolean): okhttp3.OkHttpClient {
            OkHttpProvider.configureRouteProvider {
                RouteSnapshot(route = NetworkRoute.VK, state = BackendState.READY, endpoint = SocksEndpoint(proxy.localPort, "user", "password"))
            }
            val builder = OkHttpProvider.httpClient().newBuilder().protocols(listOf(Protocol.HTTP_1_1))
            if (trusted) builder.sslSocketFactory(tls.socketFactory, trust)
            return builder.build()
        }
        override fun close() {
            OkHttpProvider.configureRouteProvider(null)
            server.close(); proxy.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow()
        }
    }

    @Test fun `TLS ends at relay while proxy sees encrypted bytes`() {
        Relay().use { relay ->
            relay.client(true).newCall(Request.Builder().url("https://relay.example.invalid/")
                .post("synthetic-private-message-and-media".toRequestBody()).build()).let { call ->
                    try { call.execute() } catch (e: IOException) {
                        throw AssertionError("server=${relay.serverError}; proxy=${relay.proxyError}; body=${relay.received}", e)
                    }
                }.use { response ->
                assertEquals(200, response.code); assertEquals("ok", response.body.string())
            }
            assertEquals("relay.example.invalid", relay.host)
            assertEquals("synthetic-private-message-and-media", relay.received)
            assertFalse(relay.observed.toString().contains("synthetic-private-message-and-media"))
        }
    }
    @Test fun `untrusted relay certificate is rejected through proxy`() {
        Relay().use { relay -> assertThrows(SSLException::class.java) {
            relay.client(false).newCall(Request.Builder().url("https://relay.example.invalid/").build()).execute()
        } }
    }
    @Test fun `trusted certificate for wrong hostname is rejected through proxy`() {
        Relay().use { relay -> assertThrows(SSLException::class.java) {
            relay.client(true).newCall(Request.Builder().url("https://wrong-relay.example.invalid/").build()).execute()
        } }
    }
    @Test fun `public websocket cannot use plaintext through protected route`() {
        try {
            OkHttpProvider.configureRouteProvider { RouteSnapshot(route = NetworkRoute.VK, state = BackendState.READY, endpoint = SocksEndpoint(1, "user", "password")) }
            val failure = assertThrows(IOException::class.java) {
                OkHttpProvider.webSocketClient().newCall(Request.Builder().url("http://relay.example.invalid/").header("Upgrade", "websocket").build()).execute()
            }
            assertEquals("Public relay connections require TLS", failure.message)
        } finally { OkHttpProvider.configureRouteProvider(null) }
    }
}
