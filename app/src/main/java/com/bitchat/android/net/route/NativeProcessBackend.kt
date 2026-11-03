package com.bitchat.android.net.route

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Native binaries receive a bounded JSON record over stdin, never command-line credentials. */
class NativeProcessBackend(
    private val executable: File,
    private val directory: File,
    private val configuration: (NetworkProfile) -> String,
    private val proxyPassword: (NetworkProfile) -> String,
    private val launcher: (File, File) -> Process = { binary, workingDirectory ->
        check(binary.isFile && binary.canExecute()) { "Native transport is not installed" }
        check(workingDirectory.isDirectory || workingDirectory.mkdirs()) {
            "Cannot prepare transport storage"
        }
        val builder =
            ProcessBuilder(binary.absolutePath)
                .directory(workingDirectory)
                .redirectError(File("/dev/null"))
        com.bitchat.android.net.bridge.AndroidTrustBundle.configureProcess(
            builder,
            workingDirectory,
        )
        builder.start()
    },
) : NetworkBackend {
    private val mutableState = MutableStateFlow(BackendState.STOPPED)
    override val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: Process? = null
    private var monitor: Job? = null

    override suspend fun start(profile: NetworkProfile): SocksEndpoint =
        withContext(Dispatchers.IO) {
            check(process == null) { "Transport is already started" }
            mutableState.value = BackendState.STARTING
            try {
                if(profile.route==NetworkRoute.VK) QwdttTelemetry.configure(profile)
                val password = proxyPassword(profile)
                SocksCredentials(profile.username, password).validate()
                val config = configuration(profile)
                require(config.toByteArray(Charsets.UTF_8).size <= 65535 && '\n' !in config) {
                    "Transport configuration is too long"
                }
                val child = launcher(executable, directory)
                process = child
                child.outputStream.write((config + "\n").toByteArray(Charsets.UTF_8))
                child.outputStream.flush()
                if (
                    withTimeout(profile.connectTimeoutSeconds * 1000L + 3000L) {
                        awaitStatus(child, profile.route)
                    } != "READY"
                )
                    throw IOException("Native transport could not connect")
                currentCoroutineContext().ensureActive()
                mutableState.value = BackendState.READY
                monitor = scope.launch {
                    child.waitFor()
                    if (process === child) mutableState.value = BackendState.ERROR
                }
                SocksEndpoint(profile.socksPort, profile.username, password)
            } catch (e: Throwable) {
                stop()
                mutableState.value = BackendState.ERROR
                if (e is CancellationException) throw e
                throw IOException("Native transport could not start")
            }
        }

    private suspend fun awaitStatus(child: Process, route: NetworkRoute): String =
        suspendCancellableCoroutine { continuation ->
            val reader =
                Thread(
                    {
                        var delivered = false
                        var startupLines = 0
                        try {
                            while (process === child) {
                                val line = readStatusLine(child.inputStream)
                                if (!delivered && ++startupLines > 128)
                                    throw IOException("Too many startup status records")
                                when {
                                    line == "READY" ->
                                        if (!delivered) {
                                            if(route == NetworkRoute.VK) QwdttTelemetry.ready()
                                            delivered = true
                                            if (continuation.isActive) continuation.resume("READY")
                                            else break
                                        }
                                    line.startsWith("STATS:") && route == NetworkRoute.VK -> QwdttTelemetry.record(line)
                                    line.startsWith("STAGE:") -> {
                                        val stage =
                                            when (line) {
                                                "STAGE:VK_CREDENTIALS" ->
                                                    NetworkStage.VK_CREDENTIALS
                                                "STAGE:TURN_ALLOCATING" ->
                                                    NetworkStage.TURN_ALLOCATING
                                                "STAGE:TURN_READY" -> NetworkStage.TURN_READY
                                                "STAGE:AUTHENTICATING" ->
                                                    NetworkStage.AUTHENTICATING
                                                "STAGE:SESSION_HANDSHAKE" ->
                                                    NetworkStage.SESSION_HANDSHAKE
                                                "STAGE:DTLS_HANDSHAKE" ->
                                                    NetworkStage.DTLS_HANDSHAKE
                                                "STAGE:DTLS_READY" -> NetworkStage.DTLS_READY
                                                "STAGE:DTLS_TIMEOUT" -> NetworkStage.DTLS_TIMEOUT
                                                "STAGE:DTLS_ERROR" -> NetworkStage.DTLS_ERROR
                                                "STAGE:PEER_PACKET" -> NetworkStage.PEER_PACKET
                                                "STAGE:CONFIG_REQUESTED" ->
                                                    NetworkStage.CONFIG_REQUESTED
                                                "STAGE:CONFIG_RECEIVED" ->
                                                    NetworkStage.CONFIG_RECEIVED
                                                "STAGE:CONFIG_PENDING" ->
                                                    NetworkStage.CONFIG_PENDING
                                                "STAGE:CONFIG_TIMEOUT" ->
                                                    NetworkStage.CONFIG_TIMEOUT
                                                "STAGE:WIREGUARD_STARTING" ->
                                                    NetworkStage.WIREGUARD_STARTING
                                                "STAGE:WIREGUARD_HANDSHAKE" ->
                                                    NetworkStage.WIREGUARD_HANDSHAKE
                                                "STAGE:WIREGUARD_READY" ->
                                                    NetworkStage.WIREGUARD_READY
                                                "STAGE:PEER_TIMEOUT" -> NetworkStage.PEER_TIMEOUT
                                                "STAGE:RAW_HANDSHAKE_RETRY" ->
                                                    NetworkStage.RAW_HANDSHAKE_RETRY
                                                "STAGE:RAW_HANDSHAKE" -> NetworkStage.RAW_HANDSHAKE
                                                "STAGE:RAW_READY" -> NetworkStage.RAW_READY
                                                "STAGE:RECONNECTING" -> NetworkStage.RECONNECTING
                                                "STAGE:TRANSPORT_READY" ->
                                                    NetworkStage.TRANSPORT_READY
                                                "STAGE:MODE_DTLS" -> NetworkStage.MODE_DTLS
                                                "STAGE:MODE_RTP" -> NetworkStage.MODE_RTP

                                                "STAGE:SESSION_TIMEOUT" ->
                                                    NetworkStage.SESSION_TIMEOUT
                                                else -> throw IOException("Invalid native stage")
                                            }
                                        if (process === child) {
                                            if(route == NetworkRoute.VK) QwdttTelemetry.phase(stage)
                                            NetworkDiagnostics.log.record(route, stage)
                                            if (delivered && stage == NetworkStage.RECONNECTING)
                                                mutableState.value = BackendState.RECONNECTING
                                            if (delivered && stage == NetworkStage.TRANSPORT_READY)
                                                mutableState.value = BackendState.READY
                                        }
                                    }
                                    line.startsWith("ERROR ") -> {
                                        NetworkDiagnostics.log.record(
                                            route,
                                            when (line) {
                                                "ERROR AUTH" -> NetworkStage.AUTH_ERROR
                                                "ERROR VK_CAPTCHA" ->
                                                    NetworkStage.VK_CONFIRMATION_REQUIRED
                                                "ERROR WG_CONFIG" -> NetworkStage.WG_CONFIG_ERROR
                                                "ERROR WG_HANDSHAKE" ->
                                                    NetworkStage.WG_HANDSHAKE_ERROR
                                                "ERROR RAW_PROTOCOL" ->
                                                    NetworkStage.RAW_PROTOCOL_ERROR

                                                else -> NetworkStage.CONNECTION_ERROR
                                            },
                                        )
                                        throw IOException("Native transport reported an error")
                                    }
                                    else -> throw IOException("Invalid native status")
                                }
                            }
                        } catch (e: Exception) {
                            if (!delivered && continuation.isActive)
                                continuation.resumeWithException(e)
                            else if (delivered && process === child && child.isAlive)
                                runCatching { child.destroyForcibly() }
                        }
                    },
                    "transport-status",
                )
            reader.isDaemon = true
            continuation.invokeOnCancellation {
                runCatching { child.destroyForcibly() }
                reader.interrupt()
            }
            reader.start()
        }

    override suspend fun stop() =
        withContext(NonCancellable + Dispatchers.IO) {
            val child = process
            process = null
            if (child != null) {
                runCatching { child.destroy() }
                if (!child.waitFor(1000, TimeUnit.MILLISECONDS)) {
                    child.destroyForcibly()
                    child.waitFor(1000, TimeUnit.MILLISECONDS)
                }
                runCatching { child.outputStream.close() }
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
            }
            monitor?.cancel()
            monitor = null
            QwdttTelemetry.reset()
            mutableState.value = BackendState.STOPPED
        }

    private fun readStatusLine(input: InputStream): String {
        val line = StringBuilder()
        repeat(256) {
            val ch = input.read()
            if (ch == -1) throw java.io.EOFException("Native transport exited")
            if (ch == 10) return line.toString()
            if (ch !in 32..126) throw IOException("Invalid native status")
            line.append(ch.toChar())
        }
        throw IOException("Invalid native status")
    }
}

fun vkBackend(binary: File, directory: File, secret: (String) -> String?): NetworkBackend =
    NativeProcessBackend(
        binary,
        directory,
        { profile ->
            require(profile.serverHost.isNotBlank()) { "VPS address is missing" }
            val hash = requireNotNull(secret(profile.secretRef("vk-hash"))) { "VK hash is missing" }
            val password =
                requireNotNull(secret(profile.secretRef("vk-password"))) {
                    "VPS password is missing"
                }
            val proxy =
                requireNotNull(secret(profile.secretRef("proxy-password"))) {
                    "Proxy password is missing"
                }
            Gson()
                .toJson(
                    mapOf(
                        "mode" to profile.vkMode.name.lowercase(),
                        "peer" to
                            java.net.InetSocketAddress.createUnresolved(
                                    profile.serverHost,
                                    profile.serverPort,
                                )
                                .let {
                                    if (':' in profile.serverHost)
                                        "[${profile.serverHost}]:${profile.serverPort}"
                                    else "${profile.serverHost}:${profile.serverPort}"
                                },
                        "autoPeer" to
                            (profile.autoPeerMode &&
                                !profile.noDtls &&
                                profile.vkMode == VkMode.WG),
                        "fallbackPort" to profile.fallbackPort,
                        "dtlsTimeoutSeconds" to profile.handshakeTimeoutSeconds,
                        "configTimeoutSeconds" to profile.configTimeoutSeconds,
                        "tunnelDns" to profile.tunnelDns,
                        "tunnelMtu" to profile.tunnelMtu,
                        "wgKeepalive" to profile.wgKeepaliveSeconds,
                        "deviceId" to profile.deviceId,
                        "hash" to hash,
                        "password" to password,
                        "port" to profile.socksPort,
                        "username" to profile.username,
                        "socksPassword" to proxy,
                        "dns" to profile.dns,
                        "turnHost" to profile.turnHost,
                        "turnPort" to profile.turnPort,
                        "turnTcp" to profile.turnTcp,
                        "noDtls" to profile.noDtls,
                        "obfuscation" to profile.obfuscation,
                    )
                )
        },
        { profile -> requireNotNull(secret(profile.secretRef("proxy-password"))) },
    )
