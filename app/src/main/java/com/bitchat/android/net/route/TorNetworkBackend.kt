package com.bitchat.android.net.route

import com.bitchat.android.net.bridge.BridgeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.torproject.arti.ArtiNative
import java.io.File

interface TorEngine {
    fun configure(directory: String, bridges: String, program: String, username: String, password: String)
    fun listen(port: Int)
    fun bootstrapProgress(): Int = if (isReady()) 100 else 0
    fun isReady(): Boolean
    fun stop()
}

private object JniTorEngine : TorEngine {
    override fun configure(directory: String, bridges: String, program: String, username: String, password: String) {
        if (bridges.lineSequence().any { it.startsWith("obfs4 ") || it.startsWith("webtunnel ") }) {
            check(File(program).isFile && File(program).canExecute()) { "Packaged Tor transport is unavailable" }
        }
        val trustPath = if (bridges.lineSequence().any { it.startsWith("webtunnel ") }) {
            val trust = File(directory, "android-trust.pem")
            com.bitchat.android.net.bridge.AndroidTrustBundle.write(trust, com.bitchat.android.net.bridge.AndroidTrustBundle.systemCertificates())
            trust.absolutePath
        } else ""
        check(ArtiNative.configureNetwork(bridges, program, username, password, trustPath) == 0) { "Tor configuration rejected" }
        check(ArtiNative.initialize(directory) == 0) { "Tor initialization failed" }
    }
    override fun listen(port: Int) { check(ArtiNative.startSocksProxy(port) == 0) { "Tor proxy could not start" } }
    override fun isReady(): Boolean = ArtiNative.isReady() == 1
    override fun bootstrapProgress(): Int = ArtiNative.getBootstrapProgress()
    override fun stop() { ArtiNative.stop() }
}

class TorNetworkBackend(
    private val directory: File,
    private val program: File,
    private val secret: (String) -> String?,
    private val engine: TorEngine = JniTorEngine,
) : NetworkBackend {
    private val mutableState = MutableStateFlow(BackendState.STOPPED)
    override val state = mutableState.asStateFlow()

    override suspend fun start(profile: NetworkProfile): SocksEndpoint = withContext(Dispatchers.IO) {
        mutableState.value = BackendState.STARTING
        try {
            val password = requireNotNull(secret(profile.secretRef("proxy-password"))) { "Proxy password is missing" }
            SocksCredentials(profile.username, password).validate()
            NetworkDiagnostics.log.record(NetworkRoute.TOR, NetworkStage.CONFIGURING)
            val bridges = BridgeParser.parse(secret(profile.secretRef("bridges")) ?: "").joinToString("\n") { it.toTorLine() }
            engine.configure(directory.absolutePath, bridges, program.absolutePath, profile.username, password)
            NetworkDiagnostics.log.record(NetworkRoute.TOR, NetworkStage.LISTENING)
            engine.listen(profile.socksPort)
            withTimeout(180_000) {
                while (!engine.isReady()) {
                    val progress = engine.bootstrapProgress()
                    if (progress == -2) throw TorBootstrapException()
                    check(progress in 0..100) { "Tor engine stopped during bootstrap" }
                    NetworkDiagnostics.log.record(NetworkRoute.TOR, NetworkStage.BOOTSTRAPPING, progress.coerceAtMost(99))
                    delay(250)
                }
            }
            NetworkDiagnostics.log.record(NetworkRoute.TOR, NetworkStage.READY, 100)
            mutableState.value = BackendState.READY
            SocksEndpoint(profile.socksPort, profile.username, password)
        } catch (e: Throwable) {
            runCatching { engine.stop() }
            val stage = when (e) {
                is kotlinx.coroutines.TimeoutCancellationException -> NetworkStage.TIMEOUT
                is kotlinx.coroutines.CancellationException -> NetworkStage.STOPPED
                is TorBootstrapException -> NetworkStage.BOOTSTRAP_ERROR
                else -> NetworkStage.CONFIGURATION_ERROR
            }
            NetworkDiagnostics.log.record(NetworkRoute.TOR, stage)
            mutableState.value = BackendState.ERROR
            throw e
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        try { engine.stop() } finally { mutableState.value = BackendState.STOPPED }
    }
}

private class TorBootstrapException : IllegalStateException("Tor bootstrap failed")
