package com.bitchat.android.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NetworkSetupPreferencesTest {
    private fun store(): NetworkSetupPreferences {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return NetworkSetupPreferences(
            context.getSharedPreferences(UUID.randomUUID().toString(), 0)
        )
    }

    @Test
    fun `initial setup works in internet only mode without a location selection`() {
        val store = store()
        assertTrue(store.internetOnly)
        assertFalse(store.completed)
        assertNull(store.region)
        assertEquals(NetworkSetupRole.USER, store.role)
        assertThrows(IllegalStateException::class.java) { store.complete() }
    }

    @Test
    fun `manual region and role persist without granting remote authority`() {
        val store = store()
        store.setRegion("s0000") // synthetic equatorial test region
        store.setRole(NetworkSetupRole.ADMIN)
        store.complete()
        assertTrue(store.completed)
        assertEquals("s0000", store.region)
        assertEquals(NetworkSetupRole.ADMIN, store.role)
        store.setRole(NetworkSetupRole.USER)
        assertTrue(store.completed)
    }

    @Test
    fun `invalid region is rejected and mesh is explicit`() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.setRegion("invalid-value") }
        store.setInternetOnly(false)
        assertFalse(store.internetOnly)
        store.clear()
        assertTrue(store.internetOnly)
        assertFalse(store.completed)
    }

    @Test
    fun `wizard resumes connection step with chosen role and synthetic region`() {
        val context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext<
                android.content.Context
            >()
        val prefs = context.getSharedPreferences(java.util.UUID.randomUUID().toString(), 0)
        val first = NetworkSetupPreferences(prefs)
        first.setRegion("s0000")
        first.setRole(NetworkSetupRole.ADMIN)
        first.setPhase(2)
        val reopened = NetworkSetupPreferences(prefs)
        assertEquals(2, reopened.phase)
        assertEquals(NetworkSetupRole.ADMIN, reopened.role)
        assertEquals("s0000", reopened.region)
        assertFalse(reopened.completed)
        reopened.complete()
        assertTrue(reopened.completed)
        reopened.clear()
        assertEquals(0, reopened.phase)
    }
}
