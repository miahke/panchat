package com.bitchat.android.security.vault

import org.junit.Assert.*
import org.junit.Test

class VaultKeySessionTest {
    private val payload = "synthetic private message".toByteArray()

    @Test fun `locked session never decrypts and stale work cannot reopen it`() {
        val session = VaultKeySession()
        assertThrows(VaultLockedException::class.java) { session.encrypt(VaultPurpose.MEDIA, "file", payload) }
        val pendingUnlock = session.generation
        session.lock()
        val candidate = ByteArray(32) { 1 }
        assertFalse(session.install(candidate, pendingUnlock))
        assertTrue(candidate.all { it == 0.toByte() })
        assertThrows(VaultLockedException::class.java) { session.decrypt(VaultPurpose.MEDIA, "file", ByteArray(29)) }
    }

    @Test fun `session owns its key and lock revokes further operations`() {
        val session = VaultKeySession()
        val key = ByteArray(32) { 2 }
        assertTrue(session.install(key, session.generation))
        assertTrue(key.all { it == 0.toByte() })
        val record = session.encrypt(VaultPurpose.CONVERSATION, "message-1", payload)
        assertArrayEquals(payload, session.decrypt(VaultPurpose.CONVERSATION, "message-1", record))
        session.lock()
        assertFalse(session.isUnlocked)
        assertThrows(VaultLockedException::class.java) { session.decrypt(VaultPurpose.CONVERSATION, "message-1", record) }
    }

    @Test fun `later unlock attempts invalidate earlier asynchronous results`() {
        val session = VaultKeySession()
        val first = session.beginUnlock()
        val second = session.beginUnlock()
        assertTrue(second > first)
        assertFalse(session.install(ByteArray(32) { 3 }, first))
        assertTrue(session.install(ByteArray(32) { 4 }, second))
        assertFalse(session.install(ByteArray(32) { 5 }, second))
    }

    @Test fun `ciphertext cannot move between purposes records or master keys`() {
        val session = VaultKeySession()
        session.install(ByteArray(32) { 6 }, session.generation)
        val record = session.encrypt(VaultPurpose.CONVERSATION, "message-1", payload)
        assertThrows(VaultRecordException::class.java) { session.decrypt(VaultPurpose.MEDIA, "message-1", record) }
        assertThrows(VaultRecordException::class.java) { session.decrypt(VaultPurpose.CONVERSATION, "message-2", record) }
        val tampered = record.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(VaultRecordException::class.java) { session.decrypt(VaultPurpose.CONVERSATION, "message-1", tampered) }
        session.install(ByteArray(32) { 7 }, session.beginUnlock())
        assertThrows(VaultRecordException::class.java) { session.decrypt(VaultPurpose.CONVERSATION, "message-1", record) }
    }
}
