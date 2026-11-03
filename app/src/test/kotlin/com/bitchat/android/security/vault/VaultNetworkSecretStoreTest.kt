package com.bitchat.android.security.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.net.route.NetworkSecretStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VaultNetworkSecretStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs() = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun openSession(value: Byte) = VaultKeySession().apply { install(ByteArray(32) { value }, generation) }

    @Test fun `vault network secrets cannot be read or written before unlock`() {
        val prefs = prefs()
        val session = VaultKeySession()
        val store = NetworkSecretStore.fromVault(prefs, { key() }, session)
        assertThrows(VaultLockedException::class.java) { store.put("profile:password", "synthetic-password") }
        assertTrue(prefs.all.isEmpty())
        assertThrows(VaultLockedException::class.java) { store.get("profile:password") }
        session.install(ByteArray(32) { 1 }, session.generation)
        store.put("profile:password", "synthetic-password")
        assertEquals("synthetic-password", store.get("profile:password"))
        assertFalse(prefs.all.toString().contains("synthetic-password"))
        session.lock()
        assertThrows(VaultLockedException::class.java) { store.get("profile:password") }
        store.clear() // panic is allowed even when locked
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `legacy secret migrates only after opening vault and preserves its value`() {
        val prefs = prefs()
        val legacy = key()
        NetworkSecretStore(prefs) { legacy }.put("profile:hash", "synthetic-call-hash")
        val before = prefs.getString("profile:hash", null)
        val session = VaultKeySession()
        val store = NetworkSecretStore.fromVault(prefs, { legacy }, session)
        assertThrows(VaultLockedException::class.java) { store.get("profile:hash") }
        assertEquals(before, prefs.getString("profile:hash", null))
        session.install(ByteArray(32) { 2 }, session.generation)
        assertEquals("synthetic-call-hash", store.get("profile:hash"))
        assertNotEquals(before, prefs.getString("profile:hash", null))
        val reopened = NetworkSecretStore.fromVault(prefs, { error("Legacy key must not be used") }, session)
        assertEquals("synthetic-call-hash", reopened.get("profile:hash"))
    }

    @Test fun `bulk migration commits only after every record authenticates`() {
        val prefs = prefs()
        val legacyKey = key()
        val legacy = NetworkSecretStore(prefs) { legacyKey }
        legacy.put("profile:first", "synthetic-first")
        legacy.put("profile:second", "synthetic-second")
        prefs.edit().putString("profile:damaged", "not base64!").commit()
        val original = prefs.all.toMap()
        val session = openSession(5)
        val store = NetworkSecretStore.fromVault(prefs, { legacyKey }, session)
        assertThrows(Exception::class.java) { store.migrateToVault() }
        assertEquals(original, prefs.all)
        prefs.edit().remove("profile:damaged").commit()
        store.migrateToVault()
        val reopened = NetworkSecretStore.fromVault(prefs, { error("No legacy key access after migration") }, session)
        assertEquals("synthetic-first", reopened.get("profile:first"))
        assertEquals("synthetic-second", reopened.get("profile:second"))
    }

    @Test fun `migration retry requires a successful commit even when memory already has new records`() {
        val real = prefs()
        val legacyKey = key()
        NetworkSecretStore(real) { legacyKey }.put("profile:password", "synthetic-password")
        var failCommit = true
        var commits = 0
        val wrapped = object : android.content.SharedPreferences by real {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = real.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        commits++
                        editor.commit() // models a visible in-memory update even when durability fails
                        return !failCommit
                    }
                }
            }
        }
        val store = NetworkSecretStore.fromVault(wrapped, { legacyKey }, openSession(7))
        assertThrows(IllegalStateException::class.java) { store.migrateToVault() }
        failCommit = false
        store.migrateToVault()
        assertEquals(2, commits)
        assertEquals("synthetic-password", store.get("profile:password"))
    }

    @Test fun `empty bridge configuration survives legacy storage and vault migration`() {
        val prefs = prefs()
        val legacyKey = key()
        val legacy = NetworkSecretStore(prefs) { legacyKey }
        legacy.put("profile:bridges", "")
        assertEquals("", legacy.get("profile:bridges"))
        val store = NetworkSecretStore.fromVault(prefs, { legacyKey }, openSession(6))
        store.migrateToVault()
        assertEquals("", store.get("profile:bridges"))
    }

    @Test fun `wrong vault and moved secret do not fall back to legacy key`() {
        val prefs = prefs()
        val store = NetworkSecretStore.fromVault(prefs, { error("Legacy key must not be used") }, openSession(3))
        store.put("profile:password", "synthetic-password")
        prefs.edit().putString("other:password", prefs.getString("profile:password", null)).commit()
        assertThrows(VaultRecordException::class.java) { store.get("other:password") }
        val wrong = NetworkSecretStore.fromVault(prefs, { error("Legacy key must not be used") }, openSession(4))
        assertThrows(VaultRecordException::class.java) { wrong.get("profile:password") }
    }
}
