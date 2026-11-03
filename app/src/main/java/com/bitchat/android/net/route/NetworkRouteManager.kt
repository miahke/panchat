package com.bitchat.android.net.route

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Publish the blocked route BEFORE stopping the previous backend. Never infer DIRECT from null. */
class NetworkRouteManager(
    private val backendFor: (NetworkRoute) -> NetworkBackend,
    private val onRouteChanged: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RouteSnapshot())
    val state = mutableState.asStateFlow()
    private var backend: NetworkBackend? = null
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null

    suspend fun select(profile: NetworkProfile) =
        withContext(Dispatchers.IO) {
            profile.validate()
            mutex.withLock {
                monitor?.cancel()
                monitor = null
                val previous = state.value
                mutableState.value =
                    RouteSnapshot(
                        state.value.generation + 1,
                        profile.id,
                        profile.route,
                        BackendState.STARTING,
                    )
                onRouteChanged()
                try {
                    backend?.stop()
                    backend = null
                    if (previous.state != BackendState.STOPPED)
                        NetworkDiagnostics.log.record(previous.route, NetworkStage.STOPPED)
                    NetworkDiagnostics.log.record(profile.route, NetworkStage.STARTING)
                    if (profile.route == NetworkRoute.DIRECT) {
                        mutableState.value = state.value.copy(state = BackendState.READY)
                    } else {
                        val selected = backendFor(profile.route)
                        backend = selected
                        val endpoint =
                            withTimeout(
                                if (profile.route == NetworkRoute.TOR) 240_000
                                else profile.connectTimeoutSeconds * 1000L + 5000
                            ) {
                                selected.start(profile)
                            }
                        mutableState.value =
                            state.value.copy(state = BackendState.READY, endpoint = endpoint)
                        monitor = monitorScope.launch {
                            selected.state.collect { backendState ->
                                if (
                                    backendState in
                                        listOf(BackendState.RECONNECTING, BackendState.READY)
                                )
                                    mutex.withLock {
                                        if (
                                            backend === selected &&
                                                backendState == BackendState.RECONNECTING &&
                                                state.value.state == BackendState.READY
                                        ) {
                                            mutableState.value =
                                                state.value.copy(
                                                    generation = state.value.generation + 1,
                                                    state = BackendState.RECONNECTING,
                                                    endpoint = null,
                                                )
                                            onRouteChanged()
                                        } else if (
                                            backend === selected &&
                                                backendState == BackendState.READY &&
                                                state.value.state == BackendState.RECONNECTING
                                        ) {
                                            mutableState.value =
                                                state.value.copy(
                                                    generation = state.value.generation + 1,
                                                    state = BackendState.READY,
                                                    endpoint = endpoint,
                                                )
                                            onRouteChanged()
                                        }
                                    }
                                if (backendState == BackendState.ERROR)
                                    mutex.withLock {
                                        if (
                                            backend === selected &&
                                                state.value.state in
                                                    listOf(
                                                        BackendState.READY,
                                                        BackendState.RECONNECTING,
                                                    )
                                        ) {
                                            mutableState.value =
                                                state.value.copy(
                                                    generation = state.value.generation + 1,
                                                    state = BackendState.ERROR,
                                                    endpoint = null,
                                                )
                                            NetworkDiagnostics.log.record(
                                                profile.route,
                                                NetworkStage.CONNECTION_ERROR,
                                            )
                                            onRouteChanged()
                                        }
                                    }
                            }
                        }
                    }
                    NetworkDiagnostics.log.record(
                        profile.route,
                        NetworkStage.READY,
                        if (profile.route == NetworkRoute.TOR) 100 else null,
                    )
                    onRouteChanged()
                } catch (e: Exception) {
                    if (
                        profile.route != NetworkRoute.TOR &&
                            NetworkDiagnostics.log.events.value.lastOrNull()?.stage !=
                                NetworkStage.AUTH_ERROR
                    ) {
                        NetworkDiagnostics.log.record(
                            profile.route,
                            when (e) {
                                is kotlinx.coroutines.TimeoutCancellationException ->
                                    NetworkStage.TIMEOUT
                                is CancellationException -> NetworkStage.STOPPED
                                else -> NetworkStage.CONNECTION_ERROR
                            },
                        )
                    }
                    mutableState.value =
                        state.value.copy(state = BackendState.ERROR, endpoint = null)
                    withContext(NonCancellable) { runCatching { backend?.stop() } }
                    backend = null
                    onRouteChanged()
                    if (e is CancellationException) throw e
                }
            }
        }

    suspend fun stop() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                monitor?.cancel()
                monitor = null
                mutableState.value =
                    state.value.copy(
                        generation = state.value.generation + 1,
                        state = BackendState.STOPPED,
                        endpoint = null,
                    )
                onRouteChanged()
                try {
                    backend?.stop()
                } finally {
                    backend = null
                    NetworkDiagnostics.log.record(state.value.route, NetworkStage.STOPPED)
                }
            }
        }
}
