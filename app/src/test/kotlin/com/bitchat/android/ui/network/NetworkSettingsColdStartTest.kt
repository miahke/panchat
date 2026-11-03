package com.bitchat.android.ui.network

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.bitchat.android.net.route.NetworkRuntime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetworkSettingsColdStartTest {
    @get:Rule val compose = createAndroidComposeRule<NetworkSettingsActivity>()

    @Test
    fun `settings opened before network initialization show loading without touching VK session`() {
        assertFalse(NetworkRuntime.loaded.value)
        compose.onNodeWithText("Загружаем настройки…").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }
}
