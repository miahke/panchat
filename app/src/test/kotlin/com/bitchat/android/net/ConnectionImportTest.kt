package com.bitchat.android.net

import com.bitchat.android.net.importprofile.ConnectionImport
import com.bitchat.android.net.importprofile.QwdttSubscriptionImport
import com.bitchat.android.net.route.NetworkRoute
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

class ConnectionImportTest {
    @Test fun `OpenFlux links are rejected after transport removal`() {
        assertThrows(IllegalArgumentException::class.java){ConnectionImport.parse("openflux://v1/synthetic")}
    }

    @Test
    fun `qwdtt server and password are populated and letter n or r never splits hashes`() {
        val source =
            """{"peer":"192.0.2.1:56003","password":"synthetic-password","hashes":"runner-synthetic-one\nrunner-synthetic-two","mode":"raw"}"""
        val result = ConnectionImport.parse(source)
        assertEquals(2, result.size)
        assertEquals("runner-synthetic-one", result[0].secrets["vk-hash"])
        assertEquals("runner-synthetic-two", result[1].secrets["vk-hash"])
        assertEquals("192.0.2.1", result[0].profile.serverHost)
        assertEquals(56003, result[0].profile.serverPort)
        assertEquals(com.bitchat.android.net.route.VkMode.RAW, result[0].profile.vkMode)
    }

    @Test
    fun `manual bridge paste becomes Tor profile without a service account`() {
        val bridge =
            "obfs4 192.0.2.2:443 " +
                "A".repeat(40) +
                " cert=" +
                Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52)) +
                " iat-mode=0"
        val result = ConnectionImport.parse(bridge).single()
        assertEquals(NetworkRoute.TOR, result.profile.route)
        assertEquals(bridge, result.secrets["bridges"])
    }

    @Test
    fun `empty unknown and unsupported profiles show failure instead of a blank editor`() {
        for (source in
            listOf(
                "",
                "not a profile",
                "openflux://v2/invalid",
                "panchan://v1/not-yet-supported",
                "x".repeat(131073),
            )) {
            assertThrows(Exception::class.java) { ConnectionImport.parse(source) }
        }
    }

    @Test
    fun `newline hashes and raw mode work for original subscription wrapper`() {
        val source =
            """{"profiles":[{"name":"Synthetic","peer":"192.0.2.1:56003","pass":"synthetic-password","vkHashes":"runner-one\nrunner-two","mode":"raw"}]}"""
        val result = QwdttSubscriptionImport.parse(source)
        assertEquals(listOf("runner-one", "runner-two"), result.map { it.secrets["vk-hash"] })
        assertEquals(com.bitchat.android.net.route.VkMode.RAW, result.first().profile.vkMode)
    }
}
