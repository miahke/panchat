package com.bitchat.android.net.route

import kotlinx.coroutines.flow.StateFlow

enum class BackendState { STOPPED, STARTING, READY, RECONNECTING, ERROR }

class SocksEndpoint(val port: Int, val username: String? = null, val password: String? = null) {
    init {
        require(port in 1..65535)
        require((username == null) == (password == null))
        if (username != null && password != null) SocksCredentials(username, password).validate()
    }
    override fun toString(): String = "SocksEndpoint(loopback:$port, credentials=[redacted])"
}

interface NetworkBackend {
    val state: StateFlow<BackendState>
    suspend fun start(profile: NetworkProfile): SocksEndpoint
    suspend fun stop()
}

data class RouteSnapshot(
    val generation: Long = 0,
    val profileId: String? = null,
    val route: NetworkRoute = NetworkRoute.TOR,
    val state: BackendState = BackendState.STOPPED,
    val endpoint: SocksEndpoint? = null,
)
