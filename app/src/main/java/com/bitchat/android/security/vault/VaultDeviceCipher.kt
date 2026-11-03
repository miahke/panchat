package com.bitchat.android.security.vault

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class VaultKeyProtection { SOFTWARE, HARDWARE, STRONGBOX, UNKNOWN }

/** Implementations may create a device key only for an explicit new-vault operation. */
internal interface VaultDeviceKeyAccess {
    fun getOrCreate(): SecretKey
    fun getExisting(): SecretKey?
    fun delete()
    fun protection(): VaultKeyProtection
}

/** Device-bound outer envelope: copying the password envelope alone is insufficient. */
internal class VaultDeviceCipher(private val keys: VaultDeviceKeyAccess) {
    private val aad = "bitchat/vault/device-envelope/v1".toByteArray(Charsets.US_ASCII)

    fun seal(passwordEnvelope: ByteArray): ByteArray {
        require(passwordEnvelope.size in 1..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreate())
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(passwordEnvelope)
    }

    fun open(envelope: ByteArray): ByteArray {
        if (envelope.size !in 30..4125 || envelope[0] != 1.toByte()) throw VaultUnlockException()
        val existing = keys.getExisting() ?: throw VaultUnlockException()
        val record = envelope.copyOf()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(128, record.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            return cipher.doFinal(record.copyOfRange(13, record.size))
        } catch (_: GeneralSecurityException) { throw VaultUnlockException() }
    }

    fun protection() = keys.protection()
}
