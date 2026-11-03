package com.bitchat.android.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.net.route.*
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NetworkProfileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `removed Cups profiles are erased without direct fallback or lost VK settings`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secretPrefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
        val secrets = NetworkSecretStore(secretPrefs) { key }
        val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
        prefs
            .edit()
            .putString(
                "profiles",
                """[{"id":"legacy-cups","route":"CUPS"},{"id":"keep-vk","route":"VK","serverHost":"192.0.2.1"}]""",
            )
            .putString("selected", "legacy-cups")
            .commit()
        secrets.put("legacy-cups:cups-key", "synthetic-key")
        secrets.put("legacy-cups:proxy-password", "synthetic-password")
        secrets.put("keep-vk:vk-password", "synthetic-vk-password")
        val store = NetworkProfileStore(prefs, secrets)
        assertEquals(listOf("keep-vk"), store.profiles.value.map { it.id })
        assertNull(store.selectedId.value)
        assertNull(secrets.get("legacy-cups:cups-key"))
        assertNull(secrets.get("legacy-cups:proxy-password"))
        assertEquals("synthetic-vk-password", secrets.get("keep-vk:vk-password"))
        assertTrue(store.hasPersistedProfiles)
        prefs
            .edit()
            .putString(
                "profiles",
                """[{"id":"legacy-cups","route":"CUPS"},{"id":"keep-vk","route":"VK","serverHost":"192.0.2.1"}]""",
            )
            .putString("selected", "keep-vk")
            .commit()
        val reopened = NetworkProfileStore(prefs, secrets)
        assertEquals("keep-vk", reopened.selectedId.value)
        assertEquals("192.0.2.1", reopened.profiles.value.single().serverHost)
    }

    @Test
    fun `deleting selected profile clears its secrets and persists removal without touching another`() =
        runTest {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val secretPrefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val secrets = NetworkSecretStore(secretPrefs) { key }
            val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val store = NetworkProfileStore(prefs, secrets)
            val one = NetworkProfile(id = "delete-one")
            val two = NetworkProfile(id = "keep-two")
            store.save(one)
            store.save(two)
            store.select(one.id)
            secrets.put(one.secretRef("proxy-password"), "synthetic-one")
            secrets.put(one.secretRef("bridges"), "synthetic-bridge")
            secrets.put(two.secretRef("proxy-password"), "synthetic-two")
            store.remove(one.id)
            val reopened = NetworkProfileStore(prefs, secrets)
            assertEquals(listOf(two.id), reopened.profiles.value.map { it.id })
            assertNull(reopened.selectedId.value)
            assertNull(secrets.get(one.secretRef("proxy-password")))
            assertNull(secrets.get(one.secretRef("bridges")))
            assertEquals("synthetic-two", secrets.get(two.secretRef("proxy-password")))
            reopened.remove(two.id)
            val empty = NetworkProfileStore(prefs, secrets)
            assertTrue(empty.profiles.value.isEmpty())
            assertTrue(empty.hasPersistedProfiles)
            assertNull(empty.selectedId.value)
        }

    @Test
    fun `invalid listening ports are rejected`() {
        for (port in listOf(-1, 0, 1023, 65536)) {
            assertThrows(IllegalArgumentException::class.java) {
                NetworkProfile(id = "test", route = NetworkRoute.VK, socksPort = port).validate()
            }
        }
    }

    @Test
    fun `empty proxy credentials cannot start a tunnel`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkProfile(id = "test", route = NetworkRoute.CUPS, username = "").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SocksCredentials("user", "").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SocksCredentials("user", "я".repeat(128)).validate()
        }
    }

    @Test
    fun `profiles survive reopening without persisting their secret values`() = runTest {
        val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
        val secretsPrefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secrets = NetworkSecretStore(secretsPrefs) { key }
        secrets.put("test:password", "synthetic-password")
        val store = NetworkProfileStore(prefs, secrets)
        val profile = NetworkProfile(id = "test", route = NetworkRoute.VK)
        store.save(profile)
        store.select("test")
        val restored = NetworkProfileStore(prefs, secrets)
        assertEquals(listOf(profile), restored.profiles.value)
        assertEquals("test", restored.selectedId.value)
        assertEquals(2, restored.profiles.value.single().workerCount)
        assertEquals("synthetic-password", secrets.get("test:password"))
        assertFalse(prefs.all.toString().contains("synthetic-password"))
        assertFalse(secretsPrefs.all.toString().contains("synthetic-password"))
        restored.clear()
        assertTrue(restored.profiles.value.isEmpty())
        assertNull(restored.selectedId.value)
        assertNull(secrets.get("test:password"))
    }

    @Test
    fun `legacy default profiles receive distinct stable transport identities`() = runTest {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secrets =
            NetworkSecretStore(context.getSharedPreferences(UUID.randomUUID().toString(), 0)) {
                key
            }
        fun migrated(): String {
            val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
            val json =
                com.google.gson
                    .Gson()
                    .toJson(NetworkProfile(id = "default", route = NetworkRoute.VK))
            val legacy =
                com.google.gson.JsonParser.parseString(json).asJsonObject.apply {
                    remove("deviceId")
                }
            prefs.edit().putString("profiles", "[$legacy]").commit()
            val first = NetworkProfileStore(prefs, secrets).profiles.value.single()
            val second = NetworkProfileStore(prefs, secrets).profiles.value.single()
            assertEquals("default", first.id)
            assertEquals(first.deviceId, second.deviceId)
            assertEquals("default:password", first.secretRef("password"))
            return first.deviceId
        }
        assertNotEquals(migrated(), migrated())
    }

    @Test
    fun `corrupt saved route never falls back to direct`() {
        val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
        prefs.edit().putString("profiles", "not-json").putString("selected", "test").commit()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secrets =
            NetworkSecretStore(context.getSharedPreferences(UUID.randomUUID().toString(), 0)) {
                key
            }
        assertThrows(IllegalStateException::class.java) { NetworkProfileStore(prefs, secrets) }
    }

    @Test
    fun `secret ciphertext tampering fails closed`() {
        val prefs = context.getSharedPreferences(UUID.randomUUID().toString(), 0)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secrets = NetworkSecretStore(prefs) { key }
        secrets.put("test:password", "synthetic-password")
        val ciphertext = prefs.getString("test:password", null)!!
        prefs.edit().putString("another:password", ciphertext).commit()
        assertThrows(Exception::class.java) { secrets.get("another:password") }
    }
}
