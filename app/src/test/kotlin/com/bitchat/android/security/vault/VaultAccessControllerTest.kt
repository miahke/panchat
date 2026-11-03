package com.bitchat.android.security.vault

import org.junit.Assert.*
import org.junit.Test

class VaultAccessControllerTest {
    private val payload = "synthetic background message".toByteArray()

    @Test fun `cold process cannot display data or receive protected work`() {
        val access = VaultAccessController()
        assertFalse(access.state.value.uiUnlocked)
        assertFalse(access.state.value.backgroundAllowed)
        assertThrows(VaultLockedException::class.java) { access.records.encrypt(VaultPurpose.CONVERSATION, "message", payload) }
    }

    @Test fun `hiding interface retains key for background receipt but requires UI reauthentication`() {
        val access = VaultAccessController()
        access.completePassword(access.beginPasswordAttempt(), ByteArray(32) { 1 })
        val record = access.records.encrypt(VaultPurpose.CONVERSATION, "message", payload)
        access.hideInterface()
        assertFalse(access.state.value.uiUnlocked)
        assertTrue(access.state.value.backgroundAllowed)
        assertArrayEquals(payload, access.records.decrypt(VaultPurpose.CONVERSATION, "message", record))
        assertTrue(access.completePassword(access.beginPasswordAttempt(), ByteArray(32) { 1 }))
        assertTrue(access.state.value.uiUnlocked)
    }

    @Test fun `password work finishing after backgrounding cannot reopen UI or cold vault`() {
        val access = VaultAccessController()
        val pending = access.beginPasswordAttempt()
        access.hideInterface()
        val key = ByteArray(32) { 2 }
        assertFalse(access.completePassword(pending, key))
        assertTrue(key.all { it == 0.toByte() })
        assertFalse(access.state.value.backgroundAllowed)
        assertFalse(access.state.value.uiUnlocked)
    }

    @Test fun `failed warm reauthentication never replaces background session key`() {
        val access = VaultAccessController()
        access.completePassword(access.beginPasswordAttempt(), ByteArray(32) { 3 })
        val record = access.records.encrypt(VaultPurpose.CONVERSATION, "message", payload)
        access.hideInterface()
        assertFalse(access.completePassword(access.beginPasswordAttempt(), ByteArray(32) { 4 }))
        assertFalse(access.state.value.uiUnlocked)
        assertTrue(access.state.value.backgroundAllowed)
        assertArrayEquals(payload, access.records.decrypt(VaultPurpose.CONVERSATION, "message", record))
    }

    @Test fun `explicit full lock revokes key and invalidates pending UI reauthentication`() {
        val access = VaultAccessController()
        access.completePassword(access.beginPasswordAttempt(), ByteArray(32) { 5 })
        val pending = access.beginPasswordAttempt()
        access.fullLock()
        assertFalse(access.completePassword(pending, ByteArray(32) { 5 }))
        assertFalse(access.state.value.backgroundAllowed)
        assertFalse(access.state.value.uiUnlocked)
        assertThrows(VaultLockedException::class.java) { access.records.encrypt(VaultPurpose.CONVERSATION, "message", payload) }
        val nextProcess = VaultAccessController()
        assertFalse(nextProcess.state.value.backgroundAllowed)
    }
}
