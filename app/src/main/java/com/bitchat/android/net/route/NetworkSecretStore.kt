package com.bitchat.android.net.route

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bitchat.android.security.vault.VaultLockedException
import com.bitchat.android.security.vault.VaultPurpose
import com.bitchat.android.security.vault.VaultRecordCipher
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM records bind secret identity; the new mode also requires an unlocked vault. */
class NetworkSecretStore private constructor(
    private val preferences: SharedPreferences,
    private val keyProvider: () -> SecretKey,
    private val vault: VaultRecordCipher?,
) {
    constructor(preferences: SharedPreferences, keyProvider: () -> SecretKey) : this(preferences, keyProvider, null)

    fun put(id: String, value: String) = synchronized(recordLock) {
        require(id.length in 1..128)
        val plaintext = value.toByteArray(Charsets.UTF_8)
        try {
            require(plaintext.size <= 65536)
            val encoded = if (vault != null) encodeVault(id, plaintext) else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
                cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
                Base64.getEncoder().encodeToString(byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext))
            }
            check(preferences.edit().putString(id, encoded).commit()) { "Could not save network secret" }
        } finally { plaintext.fill(0) }
    }

    fun get(id: String): String? = synchronized(recordLock) {
        require(id.length in 1..128)
        if (vault != null && !vault.isUnlocked) throw VaultLockedException()
        val value = preferences.getString(id, null) ?: return@synchronized null
        val record = parse(value)
        val plaintext = decrypt(id, record)
        try {
            if (vault != null && record[0] == 1.toByte()) {
                check(preferences.edit().putString(id, encodeVault(id, plaintext)).commit()) { "Could not migrate network secret" }
            }
            plaintext.toString(Charsets.UTF_8)
        } finally { plaintext.fill(0) }
    }

    /** Authenticate the whole snapshot before one atomic preferences update. */
    internal fun migrateToVault() = synchronized(recordLock) {
        val active = checkNotNull(vault) { "Vault migration requires a vault" }
        if (!active.isUnlocked) throw VaultLockedException()
        val snapshot = preferences.all
        check(snapshot.size <= 512) { "Too many stored network secrets" }
        val updates = linkedMapOf<String, String>()
        snapshot.forEach { (id, value) ->
            check(id.length in 1..128 && value is String) { "Invalid stored network secret" }
            val record = parse(value)
            val plaintext = decrypt(id, record)
            try {
                // Recommit authenticated v2 records too: a failed prior commit may have
                // changed SharedPreferences memory without making the new state durable.
                updates[id] = if (record[0] == 1.toByte()) encodeVault(id, plaintext) else value
            } finally { plaintext.fill(0) }
        }
        if (updates.isNotEmpty()) {
            val editor = preferences.edit()
            updates.forEach { (id, value) -> editor.putString(id, value) }
            check(editor.commit()) { "Could not migrate network secrets" }
        }
    }

    private fun encodeVault(id: String, plaintext: ByteArray): String {
        val active = checkNotNull(vault)
        return Base64.getEncoder().encodeToString(byteArrayOf(2) + active.encrypt(VaultPurpose.NETWORK, id, plaintext))
    }

    private fun parse(value: String): ByteArray {
        check(value.length in 1..87424) { "Invalid stored network secret" }
        val bytes = Base64.getDecoder().decode(value)
        check((bytes.size in 29..65565 && bytes[0] == 1.toByte()) ||
            (bytes.size in 30..65566 && bytes[0] == 2.toByte())) { "Invalid stored network secret" }
        return bytes
    }

    private fun decrypt(id: String, bytes: ByteArray): ByteArray {
        if (bytes[0] == 2.toByte()) {
            val active = checkNotNull(vault) { "Vault-bound secret requires an unlocked vault" }
            return active.decrypt(VaultPurpose.NETWORK, id, bytes.copyOfRange(1, bytes.size))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
    }

    fun removeProfile(id: String) = synchronized(recordLock) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("$id:") }.forEach { editor.remove(it) }
        check(editor.commit()) { "Could not remove profile secrets" }
    }

    fun clear() = synchronized(recordLock) {
        check(preferences.edit().clear().commit()) { "Could not clear network secrets" }
    }

    fun remove(id: String) = synchronized(recordLock) {
        check(preferences.edit().remove(id).commit()) { "Could not remove network secret" }
    }

    companion object {
        private val recordLock = Any()
        private const val ALIAS = "bitchat_transport_secrets_v1"

        internal fun fromVault(preferences: SharedPreferences, legacyReadKey: () -> SecretKey, vault: VaultRecordCipher): NetworkSecretStore =
            NetworkSecretStore(preferences, legacyReadKey, vault)

        /** To be selected by the vault-gated runtime, not during cold application bootstrap. */
        internal fun createWithVault(context: Context, vault: VaultRecordCipher): NetworkSecretStore = fromVault(
            context.getSharedPreferences("network_secrets", Context.MODE_PRIVATE),
            {
                synchronized(NetworkSecretStore::class.java) {
                    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    checkNotNull(store.getKey(ALIAS, null) as? SecretKey) { "Legacy network key is missing" }
                }
            }, vault
        )
        fun create(context: Context): NetworkSecretStore = NetworkSecretStore(
            context.getSharedPreferences("network_secrets", Context.MODE_PRIVATE)
        ) {
            synchronized(NetworkSecretStore::class.java) {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                ).apply {
                    init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).setRandomizedEncryptionRequired(true).build())
                }.generateKey()
            }
        }
    }
}
