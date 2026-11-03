package com.bitchat.android.ui.network

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.bitchat.android.content.ChatReadingPreferences
import com.bitchat.android.content.ContentPreferences
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.ui.MessageItem
import com.bitchat.android.ui.content.ChatReadingSettings
import com.bitchat.android.ui.content.ContentSettingsRow
import com.bitchat.android.ui.content.MessageVisibilityGate
import java.util.Date
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PanchatContentGateTest {
    private val compose = createComposeRule()
    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(
                object : ExternalResource() {
                    override fun before() {
                        val app = RuntimeEnvironment.getApplication()
                        shadowOf(app.packageManager)
                            .addActivityIfNotPresent(
                                ComponentName(app, ComponentActivity::class.java)
                            )
                    }
                }
            )
            .around(compose)

    @After
    fun resetPreferences() {
        ContentPreferences.clear(RuntimeEnvironment.getApplication())
        ChatReadingPreferences.clear(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `settings cancel preserves previous rules while save updates them`() {
        val app = RuntimeEnvironment.getApplication()
        ContentPreferences.save(app, "original", false)
        compose.setContent { MaterialTheme { ContentSettingsRow() } }
        val title = app.getString(com.bitchat.android.R.string.content_settings_title)
        compose.onNodeWithText(title).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("replacement")
        compose.onNodeWithText(app.getString(android.R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("original"), ContentPreferences.options.value.rules.keywords)
        }
        compose.onNodeWithText(title).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("replacement")
        compose
            .onNodeWithText(app.getString(com.bitchat.android.R.string.content_settings_save))
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf("replacement"), ContentPreferences.options.value.rules.keywords)
        }
    }

    @Test
    fun `reading controls persist font and timestamp choice`() {
        compose.setContent { MaterialTheme { ChatReadingSettings() } }
        compose.onNodeWithText("130%").performClick()
        compose.onNode(isToggleable() and hasClickAction()).performClick()
        compose.runOnIdle {
            assertEquals(130, ChatReadingPreferences.options.value.fontPercent)
            assertEquals(false, ChatReadingPreferences.options.value.use24Hour)
        }
    }

    @Test
    fun `actual message item covers text even when remote sender copies local nickname`() {
        ContentPreferences.save(RuntimeEnvironment.getApplication(), "synthetic", false)
        val mesh = mock<MeshService> { on { myPeerID } doReturn "local-peer" }
        val message =
            BitchatMessage(
                id = "synthetic-id",
                sender = "me",
                senderPeerID = "remote-peer",
                content = "Synthetic private body",
                timestamp = Date(0),
            )
        compose.setContent { MaterialTheme { MessageItem(message, "me", mesh) } }
        compose.onNodeWithText("Synthetic private body", substring = true).assertDoesNotExist()
        compose
            .onNodeWithText(
                RuntimeEnvironment.getApplication()
                    .getString(com.bitchat.android.R.string.content_hidden_message)
            )
            .assertIsDisplayed()
    }

    @Test
    fun `incoming image is covered before attempting to display its file`() {
        ContentPreferences.save(RuntimeEnvironment.getApplication(), "", true)
        val mesh = mock<MeshService> { on { myPeerID } doReturn "local-peer" }
        val message =
            BitchatMessage(
                id = "synthetic-image",
                sender = "reader",
                senderPeerID = "remote-peer",
                content = "synthetic-image.png",
                type = BitchatMessageType.Image,
                timestamp = Date(0),
            )
        compose.setContent { MaterialTheme { MessageItem(message, "me", mesh) } }
        compose
            .onNodeWithText(
                RuntimeEnvironment.getApplication()
                    .getString(com.bitchat.android.R.string.content_hidden_image)
            )
            .assertIsDisplayed()
        compose.onNodeWithText("synthetic-image.png", substring = true).assertDoesNotExist()
    }

    @Test
    fun `hidden content is not composed until explicit reveal and resets for another message`() {
        val identity = mutableStateOf("first")
        var compositions = 0
        compose.setContent {
            MaterialTheme {
                MessageVisibilityGate(identity.value, true, "Hidden") {
                    compositions++
                    Text("Synthetic private body")
                }
            }
        }
        compose.onNodeWithText("Synthetic private body").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, compositions) }
        compose.onNodeWithText("Hidden").performClick()
        compose.onNodeWithText("Synthetic private body").assertIsDisplayed()
        compose.runOnIdle { identity.value = "second" }
        compose.onNodeWithText("Synthetic private body").assertDoesNotExist()
    }
}
