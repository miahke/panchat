package com.bitchat.android.ui.network

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.bitchat.android.net.route.*
import com.bitchat.android.ui.theme.*
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
class PanchatVisualTest {
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
    fun reset() {
        ThemePreferenceManager.setAccent(RuntimeEnvironment.getApplication(), ThemeAccent.Green)
        QwdttTelemetry.reset()
    }

    @Test
    fun `accent buttons update the actual theme and upstream status remains readable`() {
        var activity: ComponentActivity? = null
        QwdttTelemetry.record("STATS:2:123456:456789")
        val log = NetworkDiagnosticLog()
        log.record(NetworkRoute.VK, NetworkStage.MODE_RTP)
        compose.setContent {
            var context = LocalContext.current
            while (context is ContextWrapper && context !is ComponentActivity) context =
                context.baseContext
            SideEffect { activity = context as? ComponentActivity }
            BitchatTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(360.dp).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("panchat", style = MaterialTheme.typography.headlineMedium)
                        ThemeAccentPicker()
                        QwdttStatus(
                            RouteSnapshot(route = NetworkRoute.VK, state = BackendState.READY),
                            log.events.value,
                        )
                        Button({}) { Text("Настройки мода") }
                    }
                }
            }
        }
        for (accent in ThemeAccent.entries) {
            compose.onNodeWithText(accent.label).performClick()
            compose.runOnIdle {
                org.junit.Assert.assertEquals(accent, ThemePreferenceManager.accentFlow.value)
            }
            compose.onNodeWithText("qWDTT · Подключено").assertIsDisplayed()
            val destination = System.getenv("PANCHAN_UI_SCREENSHOTS") ?: continue
            compose.runOnIdle {
                val view = checkNotNull(activity).window.decorView
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                val folder = File(destination)
                folder.mkdirs()
                File(folder, "panchat-${accent.name}.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
