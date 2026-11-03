package com.bitchat.android.net.socks

import com.bitchat.android.net.route.SocksEndpoint
import java.io.DataInputStream
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** Per-client RFC 1928/1929 socket. DNS for the destination belongs to the proxy. */
class AuthenticatedSocksSocketFactory(private val endpoint: SocksEndpoint) : SocketFactory() {
    override fun createSocket(): Socket = SocksSocket(endpoint)
    override fun createSocket(host: String, port: Int): Socket = createSocket().also {
        it.connect(InetSocketAddress.createUnresolved(host, port))
    }
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().also { it.bind(InetSocketAddress(localHost, localPort)); it.connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress!!, port)
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress!!, port, localHost, localPort)

    private class SocksSocket(private val endpoint: SocksEndpoint) : Socket(Proxy.NO_PROXY) {
        override fun connect(address: SocketAddress) = connect(address, 10_000)

        override fun connect(address: SocketAddress, timeout: Int) {
            val destination = address as? InetSocketAddress ?: throw IOException("Invalid SOCKS destination")
            val oldTimeout = soTimeout
            try {
                super.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), endpoint.port), timeout)
                soTimeout = if (timeout > 0) minOf(timeout, 10_000) else 10_000
                val input = DataInputStream(super.getInputStream())
                val out = super.getOutputStream()
                val authenticated = endpoint.username != null
                val method = if (authenticated) 2 else 0
                out.write(byteArrayOf(5, 1, method.toByte())); out.flush()
                if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != method) throw IOException("SOCKS method rejected")
                if (authenticated) {
                    val user = endpoint.username!!.toByteArray(Charsets.UTF_8)
                    val pass = endpoint.password!!.toByteArray(Charsets.UTF_8)
                    out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); out.flush()
                    if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS authentication failed")
                }
                val host = destination.hostString
                val literal = if (':' in host) InetAddress.getByName(host).address else null
                val encoded = if (literal != null) byteArrayOf(4) + literal else {
                    val name = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toByteArray(Charsets.US_ASCII)
                    if (name.size !in 1..255) throw IOException("Invalid SOCKS destination")
                    byteArrayOf(3, name.size.toByte()) + name
                }
                out.write(byteArrayOf(5, 1, 0) + encoded + byteArrayOf((destination.port ushr 8).toByte(), destination.port.toByte()))
                out.flush()
                if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0)
                    throw IOException("SOCKS connection rejected")
                val count = when (input.readUnsignedByte()) {
                    1 -> 4
                    4 -> 16
                    3 -> input.readUnsignedByte()
                    else -> throw IOException("Invalid SOCKS response")
                }
                input.readFully(ByteArray(count + 2))
                soTimeout = oldTimeout
            } catch (e: Exception) {
                runCatching { close() }
                throw if (e is IOException) e else IOException("SOCKS connection failed", e)
            }
        }
    }
}
