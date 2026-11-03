package com.bitchat.android.onboarding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ManualSetupEntryTest {
    @Test fun `first launch opens manual wizard before requesting radio or location permission`() {
        NetworkSetupPreferences.from(ApplicationProvider.getApplicationContext()).clear()
        val controller = Robolectric.buildActivity(NetworkLauncherActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        assertEquals(NetworkSetupActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        controller.pause().stop().destroy()
    }
}
