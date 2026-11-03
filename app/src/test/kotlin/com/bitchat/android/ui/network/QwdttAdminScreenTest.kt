package com.bitchat.android.ui.network

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.bitchat.android.net.deploy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QwdttAdminScreenTest {
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
    private val access =
        QwdttAccess("synthetic-secret", "Synthetic friend", "synthetic-hash", 1, 0, false, 0, 0)

    @Test
    fun `revocation requires confirmation and can be cancelled`() {
        val requests = mutableListOf<QwdttAdminRequest>()
        compose.setContent {
            MaterialTheme {
                QwdttAdminScreen(listOf(access), false, null, "192.0.2.1", { requests += it }, {})
            }
        }
        compose.onNodeWithText("Отключить").performScrollTo().performClick()
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("Отмена").performClick()
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("Отключить").performScrollTo().performClick()
        compose.onNodeWithText("Подтвердить").performClick()
        assertEquals("deactivate", requests.single().action)
        assertEquals("synthetic-secret", requests.single().fields["password"])
    }

    @Test
    fun `issue requires hash and sends configured limits`() {
        val requests = mutableListOf<QwdttAdminRequest>()
        compose.setContent {
            MaterialTheme {
                QwdttAdminScreen(emptyList(), false, null, "192.0.2.1", { requests += it }, {})
            }
        }
        compose.onNodeWithText("Выдать доступ").performClick()
        compose.onNodeWithText("Выдать").assertIsNotEnabled()
        compose.onNodeWithText("Хэш звонка VK").performTextInput("synthetic-hash")
        compose.onNodeWithText("Выдать").performClick()
        assertEquals("create", requests.single().action)
        assertEquals("1", requests.single().fields["max_devices"])
        assertEquals("30", requests.single().fields["days"])
    }

    @Test
    fun `edit sends the chosen duration and device limit`() {
        val requests = mutableListOf<QwdttAdminRequest>()
        compose.setContent {
            MaterialTheme {
                QwdttAdminScreen(listOf(access), false, null, "192.0.2.1", { requests += it }, {})
            }
        }
        compose.onNodeWithText("Изменить").performScrollTo().performClick()
        compose.onNodeWithText("Дней с сегодняшнего дня: 1–365").performTextReplacement("60")
        compose.onNodeWithText("Лимит устройств: 1–32").performTextReplacement("2")
        compose.onNodeWithText("Подтвердить").performClick()
        assertEquals("update", requests.single().action)
        assertEquals("60", requests.single().fields["days"])
        assertEquals("2", requests.single().fields["max_devices"])
    }

    @Test
    fun `deletion has its own confirmation`() {
        val requests = mutableListOf<QwdttAdminRequest>()
        compose.setContent {
            MaterialTheme {
                QwdttAdminScreen(listOf(access), false, null, "192.0.2.1", { requests += it }, {})
            }
        }
        compose.onNodeWithText("Удалить").performScrollTo().performClick()
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("Подтвердить").performClick()
        assertEquals("delete", requests.single().action)
    }
}
