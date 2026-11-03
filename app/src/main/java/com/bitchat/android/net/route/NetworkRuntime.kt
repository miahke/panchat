package com.bitchat.android.net.route

import android.app.Application
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.net.vk.VkOAuthParser
import com.bitchat.android.net.vk.VkSessionRepository
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object NetworkRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(RouteSnapshot())
    val status = mutableStatus.asStateFlow()
    private val mutableWipeEpoch = MutableStateFlow(0L)
    val wipeEpoch = mutableWipeEpoch.asStateFlow()
    private val mutableLoaded = MutableStateFlow(false)
    val loaded = mutableLoaded.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val initializationError = mutableError.asStateFlow()
    private val mutableProfiles = MutableStateFlow<List<NetworkProfile>>(emptyList())
    val profiles = mutableProfiles.asStateFlow()
    @Volatile private var application: Application? = null
    val isStarted: Boolean
        get() = application != null

    @Volatile private var connectRequested = true
    @Volatile private var manager: NetworkRouteManager? = null
    @Volatile private var controller: NetworkLifecycleController? = null
    private var initializationJob: Job? = null
    private lateinit var secrets: NetworkSecretStore
    private lateinit var store: NetworkProfileStore
    lateinit var vkSession: VkSessionRepository
        private set

    @Synchronized
    fun initialize(app: Application) {
        if (application != null) return
        application = app
        // Installed synchronously before Nostr initialization: a missing profile never implies
        // DIRECT.
        OkHttpProvider.configureRouteProvider { manager?.state?.value ?: mutableStatus.value }
        initializationJob = scope.launch {
            try {
                secrets = NetworkSecretStore.create(app)
                store =
                    NetworkProfileStore(app.getSharedPreferences("network_profiles", 0), secrets)
                vkSession = VkSessionRepository(secrets)
                val root = File(app.noBackupFilesDir, "network")
                val binaries = File(app.applicationInfo.nativeLibraryDir)
                val backends =
                    mapOf(
                        NetworkRoute.TOR to
                            TorNetworkBackend(
                                File(root, "arti"),
                                File(binaries, "libbitchat_pt.so"),
                                secrets::get,
                            ),
                        NetworkRoute.VK to
                            vkBackend(
                                File(binaries, "libbitchat_vk.so"),
                                File(root, "qwdtt"),
                                secrets::get,
                            ),
                    )
                val routeManager =
                    NetworkRouteManager({ requireNotNull(backends[it]) }) {
                        mutableStatus.value = manager?.state?.value ?: RouteSnapshot()
                        ArtiTorManager.getInstance().publishRuntimeStatus(mutableStatus.value)
                        OkHttpProvider.reset()
                        com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections()
                    }
                manager = routeManager
                controller = NetworkLifecycleController(store, routeManager, scope)
                scope.launch { store.profiles.collect { mutableProfiles.value = it } }
                if (store.profiles.value.isEmpty() && !store.hasPersistedProfiles) {
                    val profile =
                        NetworkProfile(
                            id = "default",
                            name = "Tor",
                            socksPort = 9050,
                            route =
                                if (TorPreferenceManager.get(app) == TorMode.ON) NetworkRoute.TOR
                                else NetworkRoute.DIRECT,
                        )
                    secrets.put(profile.secretRef("proxy-password"), randomPassword())
                    store.save(profile)
                    store.select(profile.id)
                }
                mutableLoaded.value = true
                if (
                    connectRequested &&
                        com.bitchat.android.onboarding.NetworkSetupPreferences.from(app).completed
                )
                    store.selectedId.value?.let { controller?.connect(it) }
            } catch (_: Throwable) {
                mutableStatus.value =
                    mutableStatus.value.copy(state = BackendState.ERROR, endpoint = null)
                mutableError.value =
                    "Network settings could not be loaded. Restart the app or clear its storage."
            }
        }
    }

    suspend fun saveAndConnect(profile: NetworkProfile, values: Map<String, String>) =
        withContext(Dispatchers.IO) {
            check(loaded.value) { "Network settings are not ready" }
            profile.validate()
            val normalized = values.toMutableMap()
            normalized["vk-hash"]
                ?.takeIf { it.isNotBlank() }
                ?.let { normalized["vk-hash"] = VkOAuthParser.callHash(it) }
            normalized["bridges"]?.let { com.bitchat.android.net.bridge.BridgeParser.parse(it) }
            if (profile.route != NetworkRoute.DIRECT)
                SocksCredentials(profile.username, values["proxy-password"].orEmpty()).validate()
            controller?.disconnect()
            normalized.forEach { (kind, value) ->
                require(kind in secretKinds)
                if (value.isEmpty()) secrets.remove(profile.secretRef(kind))
                else secrets.put(profile.secretRef(kind), value)
            }
            store.save(profile)
            connectRequested = true
            controller?.connect(profile.id)
        }

    suspend fun readSecrets(profile: NetworkProfile): Map<String, String> =
        withContext(Dispatchers.IO) {
            secretKinds.associateWith { secrets.get(profile.secretRef(it)).orEmpty() }
        }

    suspend fun resumeSelected() {
        initializationJob?.join()
        check(loaded.value) { "Network settings are not ready" }
        connectRequested = true
        if (status.value.state !in listOf(BackendState.READY, BackendState.STARTING))
            store.selectedId.value?.let { controller?.connect(it) }
    }

    suspend fun connectSaved(id: String) {
        initializationJob?.join()
        check(loaded.value) { "Network settings are not ready" }
        require(profiles.value.any { it.id == id }) { "Profile not found" }
        connectRequested = true
        checkNotNull(controller).connect(id)
    }

    suspend fun removeProfile(id: String) {
        initializationJob?.join()
        check(loaded.value) { "Network settings are not ready" }
        if (store.selectedId.value == id) connectRequested = false
        checkNotNull(controller).remove(id)
    }

    suspend fun disconnect() {
        connectRequested = false
        controller?.disconnect()
    }

    suspend fun wipe() =
        withContext(Dispatchers.IO) {
            try {
                initializationJob?.cancelAndJoin()
                if (::vkSession.isInitialized) vkSession.logout()
                if (controller != null) controller?.clear()
                else {
                    if (::secrets.isInitialized) secrets.clear()
                    application
                        ?.getSharedPreferences("network_profiles", 0)
                        ?.edit()
                        ?.clear()
                        ?.commit()
                        ?.let { check(it) }
                }
                com.bitchat.android.net.vk.VkAuthActivity.clearTransientStorage()
                application?.noBackupFilesDir?.resolve("network")?.let {
                    check(!it.exists() || it.deleteRecursively())
                }
            } finally {
                try {
                    application?.let {
                        com.bitchat.android.service.InternetForegroundService.stop(it)
                        com.bitchat.android.onboarding.NetworkSetupPreferences.from(it).clear()
                    }
                } finally {
                    NetworkDiagnostics.log.clear()
                    mutableWipeEpoch.value += 1
                }
            }
        }

    fun legacyTorMode(mode: TorMode) {
        if (!loaded.value) return
        scope.launch {
            val route = if (mode == TorMode.ON) NetworkRoute.TOR else NetworkRoute.DIRECT
            val profile =
                store.profiles.value.lastOrNull { it.route == route }
                    ?: NetworkProfile(name = route.name, route = route).also {
                        secrets.put(it.secretRef("proxy-password"), randomPassword())
                        store.save(it)
                    }
            controller?.connect(profile.id)
        }
    }

    suspend fun awaitReady(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            status.first { it.state == BackendState.READY }
            true
        } ?: false

    fun randomPassword(): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })

    val secretKinds = setOf("proxy-password", "bridges", "vk-hash", "vk-password")
}
