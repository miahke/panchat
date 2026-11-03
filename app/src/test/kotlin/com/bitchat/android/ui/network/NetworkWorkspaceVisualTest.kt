package com.bitchat.android.ui.network

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.bitchat.android.net.route.*
import com.bitchat.android.onboarding.NetworkSetupRole
import com.bitchat.android.ui.theme.BitchatTheme
import java.io.File
import org.junit.*
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkWorkspaceVisualTest {
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
    private var activity: ComponentActivity? = null

    @Test
    fun `primary flow renders with actual app theme and visible connect action`() {
        var ui by
            mutableStateOf(
                NetworkSettingsState(
                    role = NetworkSetupRole.ADMIN,
                    profiles =
                        listOf(
                            NetworkProfile(id = "synthetic-tor", name = "Мосты Tor"),
                            NetworkProfile(
                                id = "synthetic-vk",
                                name = "Мой сервер",
                                route = NetworkRoute.VK,
                                serverHost = "192.0.2.10",
                            ),
                        ),
                )
            )
        compose.setContent {
            var context = LocalContext.current
            while (context is ContextWrapper && context !is ComponentActivity) context =
                context.baseContext
            SideEffect { activity = context as? ComponentActivity }
            BitchatTheme(darkTheme = true) {
                NetworkSettingsWorkspace(
                    ui,
                    NetworkSettingsActions(),
                    Modifier.size(360.dp, 640.dp),
                )
            }
        }
        compose.onNodeWithText("Добавить подключение").assertIsDisplayed()
        capture("home")
        compose.runOnIdle { ui = ui.copy(page = NetworkPage.IMPORT, profiles = emptyList()) }
        compose.onNodeWithText("Ссылка или настройки").assertIsDisplayed()
        capture("import")
        compose.runOnIdle {
            ui =
                ui.copy(
                    page = NetworkPage.EDITOR,
                    draft =
                        ConnectionDraft(
                            NetworkProfile(name = "Готовый Tor", route = NetworkRoute.TOR),
                            mapOf("bridges" to ""),
                            imported = true,
                        ),
                )
        }
        compose.onNodeWithTag("save-connect").assertIsDisplayed()
        capture("tor-imported")
        compose.runOnIdle {
            ui =
                ui.copy(
                    draft =
                        ConnectionDraft(
                            NetworkProfile(
                                name = "Готовый VK",
                                route = NetworkRoute.VK,
                                serverHost = "192.0.2.10",
                            ),
                            mapOf("vk-hash" to "synthetic-call-hash"),
                            imported = true,
                        )
                )
        }
        compose.onNodeWithTag("save-connect").assertIsDisplayed()
        capture("vk-imported")
    }

    @Test
    fun `subscriptions render in the app theme without exposing the credential`() {
        compose.setContent {
            var context = LocalContext.current
            while (context is ContextWrapper && context !is ComponentActivity) context = context.baseContext
            SideEffect { activity = context as? ComponentActivity }
            BitchatTheme(darkTheme = true) {
                QwdttAdminScreen(listOf(com.bitchat.android.net.deploy.QwdttAccess(
                    "synthetic-secret", "Тестовый доступ", "synthetic-hash", 1, 0, false, 2048, 1024
                )), false, null, "192.0.2.1", {}, {})
            }
        }
        compose.onNodeWithText("Выдать доступ").assertIsDisplayed()
        compose.onNodeWithText("synthetic-secret").assertDoesNotExist()
        capture("subscriptions")
    }

    private fun capture(name: String) {
        val path = System.getenv("PANCHAN_UI_SCREENSHOTS") ?: return
        val directory = File(path)
        check(directory.isDirectory || directory.mkdirs())
        compose.runOnIdle {
            val view = checkNotNull(activity).window.decorView
            check(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, name + ".png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
}
