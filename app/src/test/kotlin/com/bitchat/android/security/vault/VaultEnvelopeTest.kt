package com.bitchat.android.security.vault

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class VaultEnvelopeTest {
    private fun password() = "synthetic vault passphrase".toCharArray()
    private fun masterKey() = ByteArray(32).also(SecureRandom()::nextBytes)

    @Test fun `Argon2id policy matches an independent Go x-crypto vector`() {
        val derived = VaultEnvelope.deriveWrappingKey("synthetic-vault-vector-password".toCharArray(), ByteArray(16) { it.toByte() })
        val expected = "7faf8e8f2bf6ca0d7e37d1c1d0776a2413323afa0b34b1413d5d2572638fa148"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, derived)
        derived.fill(0)
    }

    @Test fun `only the correct password opens the master key`() {
        val key = masterKey()
        val envelope = VaultEnvelope.seal(password(), key)
        assertArrayEquals(key, VaultEnvelope.open(password(), envelope))
        assertThrows(VaultUnlockException::class.java) {
            VaultEnvelope.open("different synthetic password".toCharArray(), envelope)
        }
        assertFalse(envelope.toList().windowed(key.size).any { it == key.toList() })
    }

    @Test fun `independent envelopes do not reuse salt or nonce`() {
        val key = masterKey()
        val first = VaultEnvelope.seal(password(), key)
        val second = VaultEnvelope.seal(password(), key)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(key, VaultEnvelope.open(password(), second))
    }

    @Test fun `tampering and malformed records fail closed`() {
        val original = VaultEnvelope.seal(password(), masterKey())
        for (index in listOf(0, 4, 5, 21, original.lastIndex)) {
            val changed = original.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            assertThrows(VaultUnlockException::class.java) { VaultEnvelope.open(password(), changed) }
        }
        for (invalid in listOf(byteArrayOf(), original.dropLast(1).toByteArray(), original + byteArrayOf(0))) {
            assertThrows(VaultUnlockException::class.java) { VaultEnvelope.open(password(), invalid) }
        }
    }

    @Test fun `rewrapping a master key changes the password without changing identity material`() {
        val key = masterKey()
        val old = VaultEnvelope.seal(password(), key)
        val unlocked = VaultEnvelope.open(password(), old)
        val newPassword = "new synthetic vault passphrase".toCharArray()
        val updated = VaultEnvelope.seal(newPassword, unlocked)
        unlocked.fill(0)
        assertArrayEquals(key, VaultEnvelope.open(newPassword, updated))
        assertThrows(VaultUnlockException::class.java) { VaultEnvelope.open(password(), updated) }
        assertArrayEquals(key, VaultEnvelope.open(password(), old)) // caller must atomically replace durable record
    }

    @Test fun `input bounds cannot select unbounded KDF work`() {
        assertThrows(IllegalArgumentException::class.java) { VaultEnvelope.seal(charArrayOf('x'), masterKey()) }
        assertThrows(IllegalArgumentException::class.java) { VaultEnvelope.seal(CharArray(1025) { 'x' }, masterKey()) }
        assertThrows(IllegalArgumentException::class.java) { VaultEnvelope.seal(password(), ByteArray(31)) }
        assertThrows(VaultUnlockException::class.java) { VaultEnvelope.open(CharArray(1025), ByteArray(81)) }
    }
}
