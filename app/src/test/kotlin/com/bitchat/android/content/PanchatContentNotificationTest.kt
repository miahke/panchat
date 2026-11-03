package com.bitchat.android.content

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.ui.NotificationManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PanchatContentNotificationTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @After
    fun clear() {
        ContentPreferences.clear(context)
    }

    @Test
    fun `changing filters clears pending previews so older bodies cannot reappear`() {
        ContentPreferences.clear(context)
        mockConstruction(SecureIdentityStateManager::class.java).use {
            val manager = NotificationManager(context, NotificationManagerCompat.from(context))
            manager.setAppBackgroundState(true)
            manager.showPrivateMessageNotification("peer-a", "reader", "synthetic preview")
            assertEquals(1, manager.getPendingNotificationCount())
            ContentPreferences.save(context, "synthetic", false)
            NotificationManager.clearForContentFilterChange()
            assertEquals(0, manager.getPendingNotificationCount())
        }
    }

    @Test
    fun `filtered bodies and nicknames never enter pending notification history`() {
        ContentPreferences.save(context, "synthetic block", false)
        // Android Keystore is unavailable on the host JVM; only its storage boundary is mocked.
        mockConstruction(SecureIdentityStateManager::class.java).use {
            val manager = NotificationManager(context, NotificationManagerCompat.from(context))
            manager.setAppBackgroundState(true)
            manager.showPrivateMessageNotification("peer-a", "reader", "synthetic BLOCK")
            manager.showGeohashNotification(
                "00000",
                "synthetic block#abcd",
                "ordinary",
                isMention = true,
            )
            manager.showMeshMentionNotification("reader", "SYNTHETIC BLOCK", "peer-b")
            assertEquals(0, manager.getPendingNotificationCount())
        }
    }
}
