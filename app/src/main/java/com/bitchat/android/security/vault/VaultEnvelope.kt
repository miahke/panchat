package com.bitchat.android.security.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class VaultUnlockException : GeneralSecurityException("Unable to unlock vault")

/**
 * Password-wrapped random master key, not a password verifier or an application unlock gate.
 * Version 1 fixes KDF parameters so an untrusted envelope cannot request unbounded work.
 * Platform persistence, device wrapping, lifecycle and all data repositories must be integrated
 * separately before this primitive provides application storage protection.
 */
internal object VaultEnvelope {
    private val magic = byteArrayOf(0x42, 0x43, 0x56, 0x41, 1) // BCVA, format 1
    private val purpose = "bitchat/vault/master-key/v1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_BYTES = 5 + SALT_BYTES + NONCE_BYTES
    private const val ENVELOPE_BYTES = HEADER_BYTES + KEY_BYTES + 16

    fun seal(password: CharArray, masterKey: ByteArray): ByteArray {
        require(password.size in 12..1024) { "Use a passphrase of 12 to 1024 characters" }
        require(masterKey.size == KEY_BYTES) { "Invalid vault key size" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = magic + salt + nonce
        val derived = deriveWrappingKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(purpose)
            cipher.updateAAD(header)
            return header + cipher.doFinal(masterKey)
        } finally {
            derived.fill(0)
        }
    }

    fun open(password: CharArray, envelope: ByteArray): ByteArray {
        if (password.size !in 1..1024 || envelope.size != ENVELOPE_BYTES ||
            !envelope.copyOfRange(0, magic.size).contentEquals(magic)) throw VaultUnlockException()
        // Own a snapshot of untrusted mutable input throughout authentication.
        val record = envelope.copyOf()
        val salt = record.copyOfRange(magic.size, magic.size + SALT_BYTES)
        val nonce = record.copyOfRange(magic.size + SALT_BYTES, HEADER_BYTES)
        val derived = deriveWrappingKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(purpose)
            cipher.updateAAD(record.copyOfRange(0, HEADER_BYTES))
            return cipher.doFinal(record.copyOfRange(HEADER_BYTES, record.size))
        } catch (_: GeneralSecurityException) {
            throw VaultUnlockException()
        } finally {
            derived.fill(0)
        }
    }

    internal fun deriveWrappingKey(password: CharArray, salt: ByteArray): ByteArray {
        require(password.size in 1..1024 && salt.size == SALT_BYTES)
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(65536)
            .withIterations(3)
            .withParallelism(4)
            .withSalt(salt)
            .build()
        val output = ByteArray(KEY_BYTES)
        try {
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, output)
            return output
        } catch (error: Exception) {
            output.fill(0)
            throw error
        } finally {
            parameters.clear()
        }
    }
}
