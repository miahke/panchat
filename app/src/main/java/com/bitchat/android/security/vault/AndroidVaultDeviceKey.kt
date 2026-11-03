package com.bitchat.android.security.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Per-application UID key; no serial, IMEI or user account identifiers are used. */
internal class AndroidVaultDeviceKey(context: Context) : VaultDeviceKeyAccess {
    private val supportsStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    override fun getExisting(): SecretKey? = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(ALIAS, null) as? SecretKey
    }

    override fun getOrCreate(): SecretKey = synchronized(keyLock) {
        getExisting() ?: if (supportsStrongBox) {
            try { generate(true) }
            catch (_: StrongBoxUnavailableException) { generate(false) }
        } else generate(false)
    }

    private fun generate(strongBox: Boolean): SecretKey = KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build())
    }.generateKey()

    override fun delete() = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }

    @Suppress("DEPRECATION")
    override fun protection(): VaultKeyProtection = synchronized(keyLock) {
        val key = getExisting() ?: return@synchronized VaultKeyProtection.UNKNOWN
        val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX) {
            VaultKeyProtection.STRONGBOX
        } else if (info.isInsideSecureHardware) VaultKeyProtection.HARDWARE
        else VaultKeyProtection.SOFTWARE
    }

    private companion object {
        const val ALIAS = "bitchat_vault_device_v1"
        val keyLock = Any()
    }
}
