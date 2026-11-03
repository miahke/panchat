package com.bitchat.android.security.vault

import androidx.annotation.WorkerThread
import java.security.SecureRandom

/**
 * Durable password/device key envelope. All KDF operations are serialized process-wide to bound
 * memory use; call on a worker thread. Password arguments are consumed and cleared even on failure.
 * Cold-start/lifecycle and repository integration are separate from this storage component.
 */
internal class VaultPersistence(
    private val records: VaultRecordStore,
    private val device: VaultDeviceCipher,
) {
    @WorkerThread
    fun create(password: CharArray): ByteArray {
        try {
            return synchronized(storageLock) {
                check(records.read() == null) { "Vault state already exists" }
                val key = ByteArray(32).also(SecureRandom()::nextBytes)
                try {
                    val inner = VaultEnvelope.seal(password, key)
                    try { records.write(device.seal(inner)) }
                    finally { inner.fill(0) }
                    key.copyOf()
                } finally { key.fill(0) }
            }
        } finally { password.fill('\u0000') }
    }

    @WorkerThread
    fun open(password: CharArray): ByteArray {
        try {
            return synchronized(storageLock) {
                val record = records.read() ?: throw VaultUnlockException()
                val inner = device.open(record)
                try { VaultEnvelope.open(password, inner) }
                finally { inner.fill(0) }
            }
        } finally { password.fill('\u0000') }
    }

    @WorkerThread
    fun unlockInto(password: CharArray, session: VaultKeySession, generation: Long): Boolean {
        val candidate = open(password)
        try { return session.install(candidate, generation) }
        finally { candidate.fill(0) }
    }

    @WorkerThread
    fun changePassword(oldPassword: CharArray, newPassword: CharArray) {
        // Capture before open() consumes oldPassword; callers may pass the same array twice.
        val replacement = newPassword.copyOf()
        try {
            synchronized(storageLock) {
                val key = open(oldPassword)
                try {
                    val inner = VaultEnvelope.seal(replacement, key)
                    try { records.write(device.seal(inner)) }
                    finally { inner.fill(0) }
                } finally { key.fill(0) }
            }
        } finally {
            oldPassword.fill('\u0000')
            newPassword.fill('\u0000')
            replacement.fill('\u0000')
        }
    }

    private companion object { val storageLock = Any() }
}
