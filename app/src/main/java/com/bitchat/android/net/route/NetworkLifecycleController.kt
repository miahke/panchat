package com.bitchat.android.net.route

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NetworkLifecycleController(
    private val store: NetworkProfileStore,
    private val manager: NetworkRouteManager,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var connection: Job? = null

    suspend fun connect(id: String) = mutex.withLock {
        val profile = requireNotNull(store.profiles.value.find { it.id == id }) { "Profile not found" }
        connection?.cancelAndJoin()
        store.select(id)
        connection = scope.launch { manager.select(profile) }
    }

    suspend fun disconnect() = mutex.withLock {
        connection?.cancelAndJoin()
        connection = null
        manager.stop()
    }

    suspend fun remove(id: String) = mutex.withLock {
        if (store.selectedId.value == id || manager.state.value.profileId == id) {
            connection?.cancelAndJoin()
            connection = null
            manager.stop()
        }
        store.remove(id)
    }

    suspend fun clear() = mutex.withLock {
        connection?.cancelAndJoin()
        connection = null
        manager.stop()
        store.clear()
    }
}
