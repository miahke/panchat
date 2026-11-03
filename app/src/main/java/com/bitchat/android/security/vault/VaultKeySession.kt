package com.bitchat.android.security.vault

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class VaultLockedException : IllegalStateException("Vault is locked")
internal class VaultRecordException : java.security.GeneralSecurityException("Invalid vault record")
internal enum class VaultPurpose { IDENTITY, CONVERSATION, MEDIA, NETWORK, SETTINGS, EXPORT }

/** Record encryption access without key installation, export or lifecycle mutation. */
internal interface VaultRecordCipher {
    val isUnlocked: Boolean
    fun encrypt(purpose: VaultPurpose, recordId: String, plaintext: ByteArray): ByteArray
    fun decrypt(purpose: VaultPurpose, recordId: String, envelope: ByteArray): ByteArray
}

/** Owns one in-memory master key; revocation serializes with synchronous cryptographic work. */
internal class VaultKeySession : VaultRecordCipher {
    private var epoch = 0L
    private var masterKey: ByteArray? = null

    val generation: Long @Synchronized get() = epoch
    override val isUnlocked: Boolean @Synchronized get() = masterKey != null

    /** Invalidates any earlier asynchronous KDF/biometric result before new work starts. */
    @Synchronized fun beginUnlock(): Long {
        lock()
        return epoch
    }

    /** Takes ownership and always clears the caller's bytes, including stale results. */
    @Synchronized fun install(candidate: ByteArray, expectedGeneration: Long): Boolean {
        try {
            require(candidate.size == 32) { "Invalid vault key size" }
            if (epoch != expectedGeneration) return false
            masterKey?.fill(0)
            masterKey = candidate.copyOf()
            epoch++ // A second callback cannot reuse this successful unlock generation.
            return true
        } finally {
            candidate.fill(0)
        }
    }

    @Synchronized fun lock() {
        epoch++
        masterKey?.fill(0)
        masterKey = null
    }

    /** Verify reauthentication without replacing the key used by background transports. */
    @Synchronized fun matches(candidate: ByteArray, expectedGeneration: Long): Boolean {
        val current = masterKey ?: return false
        return expectedGeneration == epoch && candidate.size == 32 &&
            java.security.MessageDigest.isEqual(current, candidate)
    }

    @Synchronized override fun encrypt(purpose: VaultPurpose, recordId: String, plaintext: ByteArray): ByteArray {
        if (masterKey == null) throw VaultLockedException()
        require(plaintext.size <= MAX_RECORD_BYTES) { "Vault record too large" }
        val aad = associatedData(purpose, recordId)
        val derived = derive(purpose)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"))
            cipher.updateAAD(aad)
            check(cipher.iv.size == 12)
            return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
        } finally { derived.fill(0) }
    }

    @Synchronized override fun decrypt(purpose: VaultPurpose, recordId: String, envelope: ByteArray): ByteArray {
        if (masterKey == null) throw VaultLockedException()
        if (envelope.size !in 29..(MAX_RECORD_BYTES + 29) || envelope[0] != 1.toByte()) throw VaultRecordException()
        val record = envelope.copyOf()
        val aad = associatedData(purpose, recordId)
        val derived = derive(purpose)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, record.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            return cipher.doFinal(record.copyOfRange(13, record.size))
        } catch (_: java.security.GeneralSecurityException) { throw VaultRecordException() }
        finally { derived.fill(0) }
    }

    private fun derive(purpose: VaultPurpose): ByteArray {
        val current = masterKey ?: throw VaultLockedException()
        return ByteArray(32).also { result ->
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(current, "bitchat/vault/v1".toByteArray(Charsets.US_ASCII), purpose.name.toByteArray(Charsets.US_ASCII)))
            }.generateBytes(result, 0, result.size)
        }
    }

    private fun associatedData(purpose: VaultPurpose, recordId: String): ByteArray {
        require(recordId.isNotEmpty() && recordId.length <= 1024 && recordId.none { it.isISOControl() }) { "Invalid vault record identity" }
        return "bitchat/vault/record/v1/${purpose.name}/$recordId".toByteArray(Charsets.UTF_8)
    }

    override fun toString(): String = "VaultKeySession([redacted])"

    private companion object { const val MAX_RECORD_BYTES = 16 * 1024 * 1024 }
}
