package com.bitchat.android.ui.network

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.bitchat.android.net.importprofile.ImportedConnection
import com.bitchat.android.net.route.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetworkWorkspaceButtonsTest {
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
    private var ui by mutableStateOf(NetworkSettingsState())
    private val events = mutableListOf<String>()

    private fun content(actions: NetworkSettingsActions) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NetworkSettingsWorkspace(ui, actions, Modifier.size(360.dp, 640.dp))
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `paste to connect journey uses the real state machine without opening a socket`() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val operations = FakeSettingsOperations()
        val store = androidx.lifecycle.ViewModelStore()
        val model = NetworkSettingsViewModel(operations)
        store.put("journey", model)
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                MaterialTheme(colorScheme = darkColorScheme()) {
                    NetworkSettingsWorkspace(
                        state,
                        NetworkSettingsActions(
                            add = model::goImport,
                            importText = model::updateImportText,
                            analyze = model::analyzeImport,
                            save = model::connectDraft,
                        ),
                        Modifier.size(360.dp, 640.dp),
                    )
                }
            }
            compose.onNodeWithText("Добавить подключение").performClick()
            val bridge =
                "webtunnel 192.0.2.5:443 " +
                    "A".repeat(40) +
                    " url=https://bridge.example.invalid/path ver=0.0.3"
            compose.onNodeWithText("Ссылка или настройки").performTextInput(bridge)
            compose.runOnIdle { dispatcher.scheduler.advanceUntilIdle() }
            compose.waitUntil(5_000) { model.state.value.page == NetworkPage.EDITOR }
            compose.onNodeWithTag("save-connect").performClick()
            compose.runOnIdle { dispatcher.scheduler.advanceUntilIdle() }
            compose.waitUntil(5_000) { model.state.value.page == NetworkPage.HOME }
            compose.runOnIdle {
                assertEquals(1, operations.saves)
                assertEquals(1, operations.imports)
                assertNull(model.state.value.draft)
                assertNull(model.state.value.error)
            }
        } finally {
            compose.runOnIdle { store.clear() }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `SSH key form requires a selected key and explicit pin confirmation`() {
        var selected by mutableStateOf(false)
        var verified by mutableStateOf(false)
        var phrase by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                ServerPreparationScreen(
                    host="192.0.2.1",port="22",pin="SHA256:"+"A".repeat(43),password="",
                    verified=verified,busy=false,message=null,hostChanged={},portChanged={},pinChanged={},passwordChanged={},
                    verifiedChanged={verified=it},probe={},deploy={events+="deploy"},cancel={},back={},
                    username="operator",keyMode=true,keySelected=selected,chooseKey={selected=true},passphrase=phrase,passphraseChanged={phrase=it}
                )
            }
        }
        compose.onNodeWithText("Установить и запустить qWDTT").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Выбрать приватный ключ").performScrollTo().performClick()
        compose.onNodeWithText("Пароль ключа — если задан").performScrollTo().performTextInput("synthetic-passphrase")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Установить и запустить qWDTT").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("deploy"),events)
        compose.onNodeWithText("Пароль SSH").assertDoesNotExist()
    }

    @Test
    fun `automatic VPS form requires verified pin and has a cancel action while busy`() {
        var verified by mutableStateOf(false)
        var busy by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                ServerPreparationScreen(
                    "192.0.2.1",
                    "22",
                    "SHA256:" + "A".repeat(43),
                    "synthetic-password",
                    verified,
                    busy,
                    null,
                    {},
                    {},
                    {},
                    {},
                    { verified = it },
                    { events += "probe" },
                    {
                        events += "deploy"
                        busy = true
                    },
                    { events += "cancel" },
                    {},
                    computerKit = { events += "kit" },
                )
            }
        }
        compose.onNodeWithText("Установить и запустить qWDTT").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Получить SSH-отпечаток").performScrollTo().performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Установить и запустить qWDTT").performScrollTo().performClick()
        compose.onNodeWithText("Установить и запустить qWDTT").assertIsNotEnabled()
        compose.onNodeWithText("Отменить").performScrollTo().performClick()
        assertEquals(listOf("probe", "deploy", "cancel"), events)
    }

    @Test
    fun `swipe requests deletion and dialog supports both cancel and confirm`() {
        val profile = NetworkProfile(id = "synthetic-swipe", name = "Synthetic Tor")
        val other = NetworkProfile(id = "synthetic-keep", name = "Keep this profile")
        ui = ui.copy(profiles = listOf(profile, other))
        content(
            NetworkSettingsActions(
                requestDelete = { ui = ui.copy(pendingDeletion = it) },
                dismissDelete = {
                    ui = ui.copy(pendingDeletion = null)
                    events += "cancel-delete"
                },
                confirmDelete = {
                    ui =
                        ui.copy(
                            pendingDeletion = null,
                            profiles = ui.profiles.filterNot { it.id == profile.id },
                        )
                    events += "delete"
                },
            )
        )
        compose.onNodeWithTag("swipe-" + profile.id).performScrollTo().performTouchInput {
            swipeLeft()
        }
        compose.onNodeWithText("Удалить подключение?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithTag("saved-" + profile.id).assertExists()
        compose.onNodeWithTag("swipe-" + profile.id).performTouchInput { swipeLeft() }
        compose.onNode(hasText("Удалить") and hasClickAction()).performClick()
        compose.onNodeWithTag("saved-" + profile.id).assertDoesNotExist()
        compose.onNodeWithTag("saved-synthetic-keep").assertExists()
        assertEquals(listOf("cancel-delete", "delete"), events)
    }

    @Test
    fun `home offers one import action Tor and preferences without exposing proxy fields`() {
        content(
            NetworkSettingsActions(
                add = { events += "import" },
                newProfile = { events += it.name },
                preferences = { events += "preferences" },
            )
        )
        compose.onNodeWithText("Добавить подключение").performClick()
        compose.onNodeWithText("Настроить мосты Tor").performClick()
        compose.onNodeWithText("Роль, область и Bluetooth").performScrollTo().performClick()
        assertEquals(listOf("import", "TOR", "preferences"), events)
        compose.onNodeWithText("Порт SOCKS5").assertDoesNotExist()
        compose.onNodeWithText("Мой сервер и подписки").assertDoesNotExist()
    }

    @Test
    fun `saved profile edit connect stop and diagnostics dispatch distinct actions`() {
        val profile = NetworkProfile(id = "synthetic-profile", name = "Synthetic Tor")
        ui =
            ui.copy(
                profiles = listOf(profile),
                connection =
                    RouteSnapshot(
                        profileId = profile.id,
                        route = NetworkRoute.TOR,
                        state = BackendState.STOPPED,
                    ),
            )
        content(
            NetworkSettingsActions(
                edit = { events += "edit" },
                connect = { events += "connect" },
                disconnect = { events += "stop" },
                diagnostics = { events += "diagnostics" },
            )
        )
        compose.onNodeWithText("Изменить").performScrollTo().performClick()
        compose.onNodeWithText("Подключить").performScrollTo().performClick()
        compose.runOnIdle {
            ui = ui.copy(connection = ui.connection.copy(state = BackendState.READY))
        }
        compose.onNodeWithText("Отключить").performScrollTo().performClick()
        compose.onNodeWithText("Сведения о подключении").performScrollTo().performClick()
        assertEquals(listOf("edit", "connect", "stop", "diagnostics"), events)
    }

    @Test
    fun `input enables import and loading disables duplicate action but exposes cancel`() {
        ui = ui.copy(page = NetworkPage.IMPORT)
        content(
            NetworkSettingsActions(
                importText = { ui = ui.copy(importText = it) },
                analyze = {
                    events += "read"
                    ui = ui.copy(operation = NetworkOperation.IMPORT)
                },
                cancel = { events += "cancel" },
            )
        )
        compose.onNodeWithText("Распознать настройки").assertIsNotEnabled()
        compose
            .onNodeWithText("Ссылка или настройки")
            .performTextInput("https://subscription.example.invalid/profile")
        compose.onNodeWithText("Загрузить подписку").performScrollTo().performClick()
        compose.onNodeWithText("Загрузить подписку").assertIsNotEnabled()
        compose.onNodeWithText("Отменить").performScrollTo().performClick()
        assertEquals(listOf("read", "cancel"), events)
    }

    @Test
    fun `multiple imported profiles remain selectable by name`() {
        val one =
            ImportedConnection(
                NetworkProfile(
                    id = "one",
                    name = "Synthetic one",
                    route = NetworkRoute.VK,
                    serverHost = "192.0.2.1",
                ),
                emptyMap(),
            )
        val two =
            ImportedConnection(
                NetworkProfile(
                    id = "two",
                    name = "Synthetic two",
                    route = NetworkRoute.VK,
                    serverHost = "192.0.2.2",
                ),
                emptyMap(),
            )
        ui = ui.copy(page = NetworkPage.IMPORT, candidates = listOf(one, two))
        content(NetworkSettingsActions(choose = { events += it.profile.id }))
        compose.onNodeWithTag("import-choice-two").performScrollTo().performClick()
        assertEquals(listOf("two"), events)
    }

    @Test
    fun `editor keeps connect visible and advanced options folded until requested`() {
        ui =
            ui.copy(
                page = NetworkPage.EDITOR,
                draft =
                    ConnectionDraft(
                        NetworkProfile(name = "Synthetic Tor"),
                        mapOf("proxy-password" to "synthetic-password"),
                    ),
            )
        content(
            NetworkSettingsActions(
                save = { events += "save" },
                advanced = {
                    ui = ui.copy(draft = ui.draft!!.copy(advanced = !ui.draft!!.advanced))
                },
                changeDraft = { ui = ui.copy(draft = it) },
            )
        )
        compose.onNodeWithTag("save-connect").assertIsDisplayed().performClick()
        compose.onNodeWithText("Порт SOCKS5").assertDoesNotExist()
        compose.onNodeWithText("Дополнительно").performScrollTo().performClick()
        compose.onNodeWithText("Порт SOCKS5").performScrollTo().performTextReplacement("1090")
        compose.runOnIdle { assertEquals("1090", ui.draft!!.proxyPort) }
        assertEquals(listOf("save"), events)
    }


    @Test
    fun `role region mesh and administrator server tools have working entry actions`() {
        ui = ui.copy(page = NetworkPage.PREFERENCES)
        content(
            NetworkSettingsActions(
                role = {
                    ui = ui.copy(role = it)
                    events += "role"
                },
                region = { events += "region" },
                mesh = { events += "mesh" },
                deployer = { events += "deployer" },
            )
        )
        compose.onNodeWithText("Администратор").performClick()
        compose.onNodeWithText("Изменить область").performScrollTo().performClick()
        compose.onNodeWithText("Настроить Bluetooth и Wi-Fi").performScrollTo().performClick()
        compose.runOnIdle { ui = ui.copy(page = NetworkPage.HOME) }
        compose.onNodeWithText("Мой сервер и подписки").performScrollTo().performClick()
        assertEquals(listOf("role", "region", "mesh", "deployer"), events)
    }



    @Test
    fun `discard dialog lets user keep editing or abandon unsaved changes`() {
        ui =
            ui.copy(
                page = NetworkPage.EDITOR,
                draft = ConnectionDraft(NetworkProfile(), emptyMap()),
                confirmDiscard = true,
            )
        content(
            NetworkSettingsActions(
                keepEditing = { events += "keep" },
                discard = { events += "discard" },
            )
        )
        compose.onNodeWithText("Продолжить настройку").performClick()
        compose.onNodeWithText("Не сохранять").performClick()
        assertEquals(listOf("keep", "discard"), events)
    }

    @Test
    fun `manual direct route requires an explicit choice`() {
        content(NetworkSettingsActions(newProfile = { events += it.name }))
        compose.onNodeWithText("Ввести параметры вручную").performScrollTo().performClick()
        compose.onNodeWithText("Cups.online — свой выход").assertDoesNotExist()
        compose.onNodeWithText("VK / qWDTT — свой сервер").assertIsDisplayed()
        compose.onNodeWithText("Напрямую к релеям").performClick()
        assertTrue(events.isEmpty())
        compose.onNodeWithText("Выбрать прямое подключение").performClick()
        assertEquals(listOf("DIRECT"), events)
    }

    @Test
    fun `deployer exports only after click and disables export while writing`() {
        var busy by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                ServerDeployerScreen(
                    busy,
                    null,
                    {
                        events += "export"
                        busy = true
                    },
                    { events += "back" },
                    cancel = {
                        events += "cancel"
                        busy = false
                    },
                )
            }
        }
        compose.onNodeWithText("Сохранить комплект деплоера").performScrollTo().performClick()
        compose.onNodeWithText("Сохраняем комплект…").assertIsNotEnabled()
        compose.onNodeWithText("Отменить сохранение").performScrollTo().performClick()
        compose.onNodeWithText("Сохранить комплект деплоера").assertIsEnabled()
        assertEquals(listOf("export", "cancel"), events)
    }
}
