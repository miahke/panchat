package com.bitchat.android.security.vault

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class VaultPersistenceTest {
    private class MemoryRecord : VaultRecordStore {
        var bytes: ByteArray? = null
        var failWrite = false
        override fun read() = bytes?.copyOf()
        override fun write(record: ByteArray) {
            if (failWrite) throw java.io.IOException("Synthetic write failure")
            bytes = record.copyOf()
        }
    }
    private class MemoryDeviceKey : VaultDeviceKeyAccess {
        private var value: SecretKey? = null
        override fun getOrCreate(): SecretKey = value ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { value = it }
        override fun getExisting() = value
        override fun delete() { value = null }
        override fun protection() = VaultKeyProtection.SOFTWARE
    }
    private fun password() = "synthetic vault passphrase".toCharArray()

    @Test fun `persistent vault requires both password and original device key`() {
        val record = MemoryRecord()
        val device = MemoryDeviceKey()
        val persistence = VaultPersistence(record, VaultDeviceCipher(device))
        val input = password()
        val created = persistence.create(input)
        assertTrue(input.all { it == '\u0000' })
        val reopened = VaultPersistence(record, VaultDeviceCipher(device))
        assertArrayEquals(created, reopened.open(password()))
        assertThrows(VaultUnlockException::class.java) { reopened.open("wrong synthetic passphrase".toCharArray()) }
        val differentDevice = VaultPersistence(record, VaultDeviceCipher(MemoryDeviceKey()))
        assertThrows(VaultUnlockException::class.java) { differentDevice.open(password()) }
        assertThrows(IllegalStateException::class.java) { reopened.create(password()) }
        created.fill(0)
    }

    @Test fun `password update is atomic and preserves the master key`() {
        val record = MemoryRecord()
        val persistence = VaultPersistence(record, VaultDeviceCipher(MemoryDeviceKey()))
        val created = persistence.create(password())
        record.failWrite = true
        assertThrows(java.io.IOException::class.java) { persistence.changePassword(password(), "new synthetic passphrase".toCharArray()) }
        assertArrayEquals(created, persistence.open(password()))
        record.failWrite = false
        persistence.changePassword(password(), "new synthetic passphrase".toCharArray())
        assertArrayEquals(created, persistence.open("new synthetic passphrase".toCharArray()))
        assertThrows(VaultUnlockException::class.java) { persistence.open(password()) }
        created.fill(0)
    }

    @Test fun `destroyed device key and damaged state never create replacement keys`() {
        val record = MemoryRecord()
        val device = MemoryDeviceKey()
        val persistence = VaultPersistence(record, VaultDeviceCipher(device))
        persistence.create(password()).fill(0)
        val original = record.bytes!!.copyOf()
        device.delete()
        assertThrows(VaultUnlockException::class.java) { persistence.open(password()) }
        assertNull(device.getExisting())
        assertArrayEquals(original, record.bytes)
        record.bytes = byteArrayOf(1)
        assertThrows(VaultUnlockException::class.java) { persistence.open(password()) }
        assertThrows(IllegalStateException::class.java) { persistence.create(password()) }
    }

    @Test fun `password changes safely handle the same input array`() {
        val persistence = VaultPersistence(MemoryRecord(), VaultDeviceCipher(MemoryDeviceKey()))
        val created = persistence.create(password())
        val same = password()
        persistence.changePassword(same, same)
        assertArrayEquals(created, persistence.open(password()))
        assertTrue(same.all { it == '\u0000' })
        created.fill(0)
    }

    @Test fun `stale password result cannot unlock a newer session`() {
        val persistence = VaultPersistence(MemoryRecord(), VaultDeviceCipher(MemoryDeviceKey()))
        persistence.create(password()).fill(0)
        val session = VaultKeySession()
        val old = session.beginUnlock()
        session.lock()
        assertFalse(persistence.unlockInto(password(), session, old))
        assertFalse(session.isUnlocked)
        assertTrue(persistence.unlockInto(password(), session, session.beginUnlock()))
        assertTrue(session.isUnlocked)
    }
}
