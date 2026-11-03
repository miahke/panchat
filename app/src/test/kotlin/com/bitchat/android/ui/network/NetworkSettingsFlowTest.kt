package com.bitchat.android.ui.network

import androidx.lifecycle.ViewModelStore
import com.bitchat.android.net.importprofile.*
import com.bitchat.android.net.route.*
import com.bitchat.android.onboarding.NetworkSetupRole
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSettingsFlowTest {
    private val store = ViewModelStore()

    @Before
    fun before() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun after() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun model(ops: FakeSettingsOperations = FakeSettingsOperations()) =
        NetworkSettingsViewModel(ops).also { store.put("model", it) }

    @Test
    fun `profile deletion requires confirmation and can be cancelled`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        val profile = NetworkProfile(id = "synthetic-delete")
        model.requestDelete(profile)
        model.dismissDelete()
        advanceUntilIdle()
        assertTrue(ops.removed.isEmpty())
        model.requestDelete(profile)
        model.confirmDelete()
        advanceUntilIdle()
        assertEquals(listOf(profile.id), ops.removed)
        assertNull(model.state.value.pendingDeletion)
    }

    @Test
    fun `one pasted local subscription opens filled editor without extra import buttons`() =
        runTest {
            val ops = FakeSettingsOperations()
            val model = model(ops)
            model.goImport()
            model.updateImportText(
                """{"peer":"192.0.2.1:56001","password":"synthetic-password","hashes":"synthetic-hash"}"""
            )
            advanceUntilIdle()
            assertEquals(NetworkPage.EDITOR, model.state.value.page)
            val draft = checkNotNull(model.state.value.draft)
            assertEquals("192.0.2.1", draft.profile.serverHost)
            assertEquals("synthetic-password", draft.secrets["vk-password"])
            assertEquals(0, ops.saves)
        }

    @Test
    fun `multiple subscription choices require selection and do not connect silently`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        model.goImport()
        model.updateImportText(
            """{"profiles":[{"peer":"192.0.2.1:56001","password":"synthetic-one"},{"peer":"192.0.2.2:56001","password":"synthetic-two"}]}"""
        )
        advanceUntilIdle()
        assertEquals(NetworkPage.IMPORT, model.state.value.page)
        assertEquals(2, model.state.value.candidates.size)
        model.chooseImport(model.state.value.candidates[1])
        assertEquals("192.0.2.2", model.state.value.draft!!.profile.serverHost)
        assertEquals(0, ops.saves)
    }

    @Test
    fun `HTTPS never fetches during typing and explicit read can be cancelled without stale results`() =
        runTest {
            val ops = FakeSettingsOperations()
            val late = CompletableDeferred<List<ImportedConnection>>()
            ops.importer = { withContext(NonCancellable) { late.await() } }
            val model = model(ops)
            model.goImport()
            model.updateImportText("https://subscription.example.invalid/profile")
            advanceUntilIdle()
            assertEquals(0, ops.imports)
            model.analyzeImport()
            runCurrent()
            assertTrue(model.state.value.busy)
            model.cancelOperation()
            model.newProfile(NetworkRoute.TOR)
            late.complete(
                listOf(ImportedConnection(NetworkProfile(route = NetworkRoute.VK), emptyMap()))
            )
            advanceUntilIdle()
            assertEquals(NetworkRoute.TOR, model.state.value.draft!!.profile.route)
            assertFalse(model.state.value.busy)
        }

    @Test
    fun `bad input leaves import visible with useful error and no connection`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        model.goImport()
        model.updateImportText("invalid")
        advanceUntilIdle()
        model.analyzeImport()
        advanceUntilIdle()
        assertEquals(NetworkPage.IMPORT, model.state.value.page)
        assertNotNull(model.state.value.error)
        assertEquals(0, ops.saves)
    }

    @Test
    fun `missing VK server highlights fields before touching runtime`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        model.newProfile(NetworkRoute.VK)
        model.connectDraft()
        advanceUntilIdle()
        assertEquals(NetworkPage.EDITOR, model.state.value.page)
        assertTrue(model.state.value.draft!!.errors.containsKey("server"))
        assertEquals(0, ops.saves)
    }

    @Test
    fun `double tap saves once and successful save returns home`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        model.newProfile(NetworkRoute.TOR)
        model.connectDraft()
        model.connectDraft()
        advanceUntilIdle()
        assertEquals(1, ops.saves)
        assertEquals(NetworkPage.HOME, model.state.value.page)
    }

    @Test
    fun `unsaved changes require confirmation and discard clears credentials`() = runTest {
        val model = model()
        model.newProfile(NetworkRoute.VK)
        model.updateSecret("vk-password", "synthetic-secret")
        model.back()
        assertTrue(model.state.value.confirmDiscard)
        model.keepEditing()
        assertNotNull(model.state.value.draft)
        model.back()
        model.discardDraft()
        assertEquals(NetworkPage.HOME, model.state.value.page)
        assertNull(model.state.value.draft)
    }

    @Test
    fun `role is saved and failed update does not pretend success`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        model.changeRole(NetworkSetupRole.ADMIN)
        advanceUntilIdle()
        assertEquals(NetworkSetupRole.ADMIN, ops.savedRole)
        ops.roleFailure = true
        model.changeRole(NetworkSetupRole.USER)
        advanceUntilIdle()
        assertEquals(NetworkSetupRole.ADMIN, model.state.value.role)
        assertNotNull(model.state.value.error)
    }



    @Test
    fun `a hidden invalid proxy port expands advanced controls before saving`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        model.newProfile(NetworkRoute.TOR)
        model.updateDraft { it.copy(proxyPort = "invalid") }
        model.connectDraft()
        advanceUntilIdle()
        assertTrue(model.state.value.draft!!.advanced)
        assertEquals(0, ops.saves)
    }

    @Test
    fun `invalid call hash is identified before calling the backend`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        model.newProfile(NetworkRoute.VK)
        model.updateDraft { it.copy(profile = it.profile.copy(serverHost = "192.0.2.1")) }
        model.updateSecret("vk-password", "synthetic-password")
        model.updateSecret("vk-hash", "bad")
        model.connectDraft()
        advanceUntilIdle()
        assertTrue(model.state.value.draft!!.errors.containsKey("vk-hash"))
        assertEquals(0, ops.saves)
    }

    @Test
    fun `explicit Tor bootstrap automatically resumes the pending HTTPS subscription`() = runTest {
        val ops = FakeSettingsOperations()
        ops.importer = {
            listOf(
                ImportedConnection(
                    NetworkProfile(route = NetworkRoute.VK),
                    mapOf("vk-password" to "synthetic-password"),
                )
            )
        }
        val model = model(ops)
        model.goImport()
        model.updateImportText("https://subscription.example.invalid/profile")
        model.newProfile(NetworkRoute.TOR)
        assertNotNull(model.state.value.pendingSubscription)
        model.connectDraft()
        advanceUntilIdle()
        model.resumePendingSubscription(
            RouteSnapshot(route = NetworkRoute.TOR, state = BackendState.READY)
        )
        advanceUntilIdle()
        assertEquals(1, ops.imports)
        assertEquals(NetworkRoute.VK, model.state.value.draft!!.profile.route)
        assertNull(model.state.value.pendingSubscription)
    }

    @Test
    fun `pending import waits for completed Tor editor and a ready Tor route`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        model.goImport()
        model.updateImportText("https://subscription.example.invalid/profile")
        model.newProfile(NetworkRoute.TOR)
        model.resumePendingSubscription(
            RouteSnapshot(route = NetworkRoute.TOR, state = BackendState.READY)
        )
        advanceUntilIdle()
        assertEquals(NetworkPage.EDITOR, model.state.value.page)
        assertEquals(0, ops.imports)
        assertNotNull(model.state.value.pendingSubscription)
        model.connectDraft()
        advanceUntilIdle()
        for (route in listOf(NetworkRoute.DIRECT, NetworkRoute.VK)) {
            model.resumePendingSubscription(
                RouteSnapshot(route = route, state = BackendState.READY)
            )
        }
        model.resumePendingSubscription(
            RouteSnapshot(route = NetworkRoute.TOR, state = BackendState.STARTING)
        )
        advanceUntilIdle()
        assertEquals(0, ops.imports)
        assertNotNull(model.state.value.pendingSubscription)
    }

    @Test
    fun `cancelling pending subscription does not fetch it after Tor becomes ready`() = runTest {
        val ops = FakeSettingsOperations()
        val model = model(ops)
        model.goImport()
        model.updateImportText("https://subscription.example.invalid/profile")
        model.newProfile(NetworkRoute.TOR)
        model.connectDraft()
        advanceUntilIdle()
        model.cancelPendingSubscription()
        model.resumePendingSubscription(
            RouteSnapshot(route = NetworkRoute.TOR, state = BackendState.READY)
        )
        advanceUntilIdle()
        assertEquals(0, ops.imports)
        assertNull(model.state.value.pendingSubscription)
    }
}

class FakeSettingsOperations : NetworkSettingsOperations {
    val removed = mutableListOf<String>()

    override suspend fun removeProfile(id: String) {
        removed += id
    }

    var saves = 0
    var imports = 0
    var savedRole = NetworkSetupRole.USER
    var roleFailure = false
    var importer: suspend (String) -> List<ImportedConnection> = { ConnectionImport.parse(it) }
    override val initialRole
        get() = savedRole

    override suspend fun importSource(source: String): List<ImportedConnection> {
        imports++
        return importer(source)
    }

    override suspend fun readSecrets(profile: NetworkProfile) = emptyMap<String, String>()

    override suspend fun saveAndConnect(profile: NetworkProfile, secrets: Map<String, String>) {
        saves++
    }

    override suspend fun connectSaved(id: String) {}

    override suspend fun disconnect() {}

    override suspend fun generateHash() = "synthetic-call-hash"


    override suspend fun logout() {}

    override suspend fun saveRole(role: NetworkSetupRole) {
        if (roleFailure) error("synthetic storage failure")
        savedRole = role
    }

    override fun randomPassword() = "synthetic-proxy-password"
}
