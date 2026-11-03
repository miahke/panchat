package com.bitchat.android.net.deploy

import org.junit.Assert.*
import org.junit.Test

class PreparationAuthenticationTest {
    @Test
    fun `invalid target also erases caller owned credentials`() {
        val bytes = "synthetic".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            JschPreparationConnection()
                .connect(
                    PreparationTarget("192.0.2.1", 22),
                    PreparationAuthentication.Password(bytes),
                )
        }
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(
            "com.jcraft.jsch.bc.SignatureEd25519",
            com.jcraft.jsch.JSch.getConfig("ssh-ed25519"),
        )
    }

    @Test
    fun `private key input has strict size bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            readPreparationPrivateKey(byteArrayOf().inputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            readPreparationPrivateKey(ByteArray(262145).inputStream())
        }
        assertArrayEquals(
            byteArrayOf(1, 2),
            readPreparationPrivateKey(byteArrayOf(1, 2).inputStream()),
        )
    }

    @Test
    fun `sudo command quoting preserves literal shell characters`() {
        assertEquals("'a'\\''b'", quoteSshCommand("a'b"))
    }

    @Test
    fun `credentials are redacted and erased after use`() {
        val key = "synthetic-private-key".toByteArray()
        val phrase = "synthetic-passphrase".toByteArray()
        val auth = PreparationAuthentication.PrivateKey(key, phrase)
        assertFalse(auth.toString().contains("synthetic"))
        auth.close()
        assertTrue(key.all { it == 0.toByte() })
        assertTrue(phrase.all { it == 0.toByte() })
    }

    @Test
    fun `SSH login accepts account names but refuses command syntax`() {
        assertEquals("operator", PreparationTarget("192.0.2.1", 22, username = "operator").username)
        for (user in listOf("-option", "root;id", "a b", "a@b", "a\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                PreparationTarget("192.0.2.1", 22, username = user)
            }
        }
    }

    @Test
    fun `authentication modes cannot silently fall back to another credential`() {
        PreparationAuthentication.Password("synthetic".toByteArray()).use {
            assertEquals("password", it.methods)
        }
        PreparationAuthentication.PrivateKey("synthetic".toByteArray()).use {
            assertEquals("publickey", it.methods)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreparationAuthentication.Password(byteArrayOf())
        }
    }
}
