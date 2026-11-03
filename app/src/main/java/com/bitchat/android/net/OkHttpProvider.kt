package com.bitchat.android.net

import okhttp3.OkHttpClient
import com.bitchat.android.net.route.BackendState
import com.bitchat.android.net.route.NetworkRoute
import com.bitchat.android.net.route.RouteSnapshot
import com.bitchat.android.net.socks.AuthenticatedSocksSocketFactory
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized OkHttp provider to ensure all network traffic honors Tor settings.
 */
object OkHttpProvider {
    enum class Route {
        DIRECT,
        TOR,
        VK
    }

    data class RoutedClient(
        val client: OkHttpClient,
        val route: Route
    )

    private val httpClientRef = AtomicReference<RoutedClient?>(null)
    private val wsClientRef = AtomicReference<OkHttpClient?>(null)
    private val clientLock = Any()
    @Volatile private var routeProvider: (() -> RouteSnapshot)? = null

    internal fun configureRouteProvider(provider: (() -> RouteSnapshot)?) {
        synchronized(clientLock) {
            routeProvider = provider
            reset()
        }
    }

    fun reset() {
        synchronized(clientLock) {
            httpClientRef.getAndSet(null)?.client?.let {
                it.dispatcher.cancelAll()
                it.connectionPool.evictAll()
            }
            wsClientRef.getAndSet(null)?.let {
                it.dispatcher.cancelAll()
                it.connectionPool.evictAll()
            }
        }
    }

    fun httpClient(): OkHttpClient = routedHttpClient().client

    /**
     * Returns the client and the route it was actually built with as one snapshot.
     *
     * The selected Tor mode can change while an existing client is still cached. Consumers that
     * key cooldowns by network identity must use this value rather than re-reading the preference.
     */
    fun routedHttpClient(): RoutedClient {
        httpClientRef.get()?.let { return it }
        return synchronized(clientLock) {
            httpClientRef.get() ?: run {
                val (builder, route) = baseBuilderForCurrentProxy()
                val client = builder
                    .callTimeout(15, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                RoutedClient(client, route).also(httpClientRef::set)
            }
        }
    }

    fun webSocketClient(): OkHttpClient {
        wsClientRef.get()?.let { return it }
        return synchronized(clientLock) {
            wsClientRef.get() ?: baseBuilderForCurrentProxy().first
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
                .also(wsClientRef::set)
        }
    }

    private fun baseBuilderForCurrentProxy(): Pair<OkHttpClient.Builder, Route> {
        val builder = OkHttpClient.Builder()
            .followSslRedirects(false)
        routeProvider?.let { provider ->
            val snapshot = provider()
            builder.addInterceptor { chain ->
                val current = provider()
                if (current.generation != snapshot.generation) throw IOException("Network route changed")
                if (current.state != BackendState.READY || snapshot.state != BackendState.READY ||
                    (snapshot.route != NetworkRoute.DIRECT && snapshot.endpoint == null)) {
                    throw IOException("Selected network route is not ready")
                }
                val request = chain.request()
                if (request.header("Upgrade").equals("websocket", true) && !request.url.isHttps &&
                    !(snapshot.route == NetworkRoute.TOR && request.url.host.endsWith(".onion"))) {
                    throw IOException("Public relay connections require TLS")
                }
                chain.proceed(chain.request())
            }
            builder.proxy(Proxy.NO_PROXY)
            if (snapshot.route != NetworkRoute.DIRECT) {
                snapshot.endpoint?.let { endpoint ->
                    builder.socketFactory(AuthenticatedSocksSocketFactory(endpoint))
                }
                // A labelled placeholder preserves the hostname for TLS/SOCKS without resolving
                // it on the local network. It is never used as the actual TCP destination.
                builder.dns { hostname -> listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0))) }
            }
            return builder to when (snapshot.route) {
                NetworkRoute.DIRECT -> Route.DIRECT
                NetworkRoute.TOR -> Route.TOR
                NetworkRoute.VK -> Route.VK
                NetworkRoute.CUPS -> throw IllegalStateException("Removed network route")
            }
        }
        val torProvider = ArtiTorManager.getInstance()
        val socks: InetSocketAddress? = torProvider.currentSocksAddress()
        // If a SOCKS address is defined, always use it. TorProvider sets this as soon as Tor mode is ON,
        // even before bootstrap, to prevent any direct connections from occurring.
        if (socks != null) {
            val proxy = Proxy(Proxy.Type.SOCKS, socks)
            builder.proxy(proxy)
        }
        return builder to if (socks == null) Route.DIRECT else Route.TOR
    }
}
