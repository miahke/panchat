package com.bitchat.android.net

import com.bitchat.android.net.importprofile.QwdttSubscriptionImport
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class QwdttSubscriptionImportTest {
    @Test
    fun `imports original JSON and expands hashes without copying device identity`() {
        val json =
            """{"profiles":[{"name":"Synthetic","peer":"192.0.2.1:56001","password":"synthetic-password","hashes":"synthetic-hash-one,synthetic-hash-two","workers":9}]}"""
        val result = QwdttSubscriptionImport.parse(json)
        assertEquals(2, result.size)
        assertEquals(2, result[0].profile.workerCount)
        assertNotEquals(result[0].profile.deviceId, result[1].profile.deviceId)
        assertEquals("synthetic-hash-two", result[1].secrets["vk-hash"])
        assertFalse(result[0].toString().contains("synthetic-password"))
        assertEquals(
            2,
            QwdttSubscriptionImport.parse(Base64.getEncoder().encodeToString(json.toByteArray()))
                .size,
        )
    }

    @Test
    fun `imports original qwdtt URI without resolving server names`() {
        val result =
            QwdttSubscriptionImport.parse(
                "qwdtt://config?peer=server.example.invalid%3A56001&pass=synthetic-password&hashes=synthetic-hash-one&name=Synthetic"
            )
        assertEquals("server.example.invalid", result.single().profile.serverHost)
        assertEquals(56001, result.single().profile.serverPort)
    }

    @Test
    fun `accepts upstream admin link with bare peer and local proxy port`() {
        val result =
            QwdttSubscriptionImport.parse(
                "qwdtt://config?name=qWDTT+-+Main&peer=192.0.2.8&hashes=synthetic_hash_one_abcdefghijklmnop%2Csynthetic_hash_two_abcdefghijklmnop&workers=9&port=9000&pass=synthetic-password"
            )
        assertEquals(2, result.size)
        result.forEach {
            assertEquals("192.0.2.8", it.profile.serverHost)
            assertEquals(56000, it.profile.serverPort)
            assertEquals(9000, it.profile.socksPort)
            assertEquals(2, it.profile.workerCount)
        }
        assertEquals("qWDTT - Main #1", result[0].profile.name)
    }

    @Test
    fun `keeps explicit peer port separate from local SOCKS port`() {
        val p =
            QwdttSubscriptionImport.parse(
                    "qwdtt://config?peer=%5B2001%3Adb8%3A%3A1%5D%3A56003&port=9001&pass=synthetic-password"
                )
                .single()
                .profile
        assertEquals("2001:db8::1", p.serverHost)
        assertEquals(56003, p.serverPort)
        assertEquals(9001, p.socksPort)
    }

    @Test
    fun `rejects duplicate fields oversized payload and malformed servers`() {
        for (input in
            listOf(
                "qwdtt://config?peer=bad/path&pass=synthetic",
                "qwdtt://config?peer=192.0.2.1%3A56001&peer=192.0.2.2%3A56001&pass=synthetic",
                "x".repeat(131073),
            )) {
            assertThrows(Exception::class.java) { QwdttSubscriptionImport.parse(input) }
        }
    }
}
