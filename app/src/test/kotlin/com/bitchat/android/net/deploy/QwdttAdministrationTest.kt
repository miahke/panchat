package com.bitchat.android.net.deploy

import com.bitchat.android.net.importprofile.QwdttSubscriptionImport
import org.junit.Assert.*
import org.junit.Test

class QwdttAdministrationTest {
    @Test
    fun `issued link round trips through real subscription importer`() {
        val entry =
            QwdttAccess("synthetic-secret", "Test + friend", "synthetic-hash", 2, 0, false, 12, 34)
        val link = entry.subscription("192.0.2.1")
        val imported = QwdttSubscriptionImport.parse(link).single()
        assertEquals("192.0.2.1", imported.profile.serverHost)
        assertEquals("synthetic-secret", imported.secrets["vk-password"])
        assertFalse(entry.toString().contains("synthetic-secret"))
    }

    @Test
    fun `management refuses arbitrary routes and shell fields`() {
        assertThrows(IllegalArgumentException::class.java) { QwdttAdminRequest("/etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) {
            QwdttAdminRequest("create", mapOf("command" to "id"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QwdttAdminRequest("create", mapOf("days" to "999"))
        }
    }

    @Test
    fun `response parser bounds and redacts access credentials`() {
        assertThrows(IllegalArgumentException::class.java) {
            QwdttAdministration.parse("x".repeat(32769))
        }
        val entries =
            QwdttAdministration.parse(
                """{"passwords":[{"password":"synthetic-secret","label":"friend","vk_hash":"hash","max_devices":1}]}"""
            )
        assertEquals(1, entries.size)
        assertFalse(entries.toString().contains("synthetic-secret"))
    }
}
