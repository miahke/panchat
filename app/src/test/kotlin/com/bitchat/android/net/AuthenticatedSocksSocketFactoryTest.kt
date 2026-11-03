package com.bitchat.android.net

import com.bitchat.android.net.route.SocksEndpoint
import com.bitchat.android.net.socks.AuthenticatedSocksSocketFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthenticatedSocksSocketFactoryTest {
    @Test fun `authenticates and sends domain to proxy without resolving it locally`() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()
                        assertEquals(listOf(5, 1, 2), List(3) { input.readUnsignedByte() })
                        out.write(byteArrayOf(5, 2)); out.flush()
                        assertEquals(1, input.readUnsignedByte())
                        assertEquals("test-user", String(input.readNBytes(input.readUnsignedByte())))
                        assertEquals("test-password", String(input.readNBytes(input.readUnsignedByte())))
                        out.write(byteArrayOf(1, 0)); out.flush()
                        assertEquals(listOf(5, 1, 0, 3), List(4) { input.readUnsignedByte() })
                        val host = String(input.readNBytes(input.readUnsignedByte()))
                        assertEquals(443, input.readUnsignedShort())
                        out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 1, 1)); out.flush()
                        assertEquals(42, input.readUnsignedByte())
                        host
                    }
                }
                val endpoint = SocksEndpoint(server.localPort, "test-user", "test-password")
                AuthenticatedSocksSocketFactory(endpoint).createSocket().use { socket ->
                    socket.connect(InetSocketAddress.createUnresolved("relay.example.invalid", 443), 3000)
                    socket.getOutputStream().write(42)
                    assertTrue(socket.isConnected)
                }
                assertEquals("relay.example.invalid", request.get(4, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `proxy cannot downgrade required authentication to anonymous`() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit<Int> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        socket.getInputStream().readNBytes(3)
                        socket.getOutputStream().write(byteArrayOf(5, 0))
                        socket.getInputStream().read()
                    }
                }
                val socket = AuthenticatedSocksSocketFactory(SocksEndpoint(server.localPort, "user", "password")).createSocket()
                assertThrows(java.io.IOException::class.java) {
                    socket.connect(InetSocketAddress.createUnresolved("relay.example.invalid", 443), 3000)
                }
                assertTrue(socket.isClosed)
                assertEquals(-1, peer.get(4, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
}
