package com.bitchat.android.net.importprofile

import com.bitchat.android.net.OkHttpProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Explicit HTTPS import through a working protected route. Local data never needs network. */
class QwdttSubscriptionLoader(
    private val createCall: (OkHttpClient, Request) -> Call = { client, request ->
        client.newCall(request)
    },
    private val client: () -> OkHttpProvider.RoutedClient = { OkHttpProvider.routedHttpClient() },
) {
    suspend fun load(input: String): List<ImportedVkProfile> =
        QwdttSubscriptionImport.parse(readSource(input))

    suspend fun readSource(input: String): String =
        withContext(Dispatchers.IO) {
            require(input.length <= 131072)
            val source = input.trim()
            if (!source.startsWith("https://") && !source.startsWith("http://"))
                return@withContext source
            require(source.length <= 8192 && source.startsWith("https://")) {
                "Subscription URL requires HTTPS"
            }
            val request = Request.Builder().url(source).get().build()
            require(
                request.url.username.isEmpty() &&
                    request.url.password.isEmpty() &&
                    request.url.fragment == null
            )
            val routed = client()
            check(routed.route != OkHttpProvider.Route.DIRECT) { "A protected route is required" }
            val http =
                routed.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            coroutineScope {
                val call = createCall(http, request)
                val completed = AtomicBoolean(false)
                val cancellation =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            if (!completed.get()) call.cancel()
                        }
                    }
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful) { "Subscription request failed" }
                        val body = checkNotNull(response.body)
                        check(body.contentLength() <= 131072) { "Subscription is too large" }
                        val bytes = ByteArrayOutputStream()
                        body.byteStream().use { stream ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                val n = stream.read(buffer)
                                if (n < 0) break
                                check(n > 0 && bytes.size() + n <= 131072) {
                                    "Subscription is too large"
                                }
                                bytes.write(buffer, 0, n)
                            }
                        }
                        bytes.toString("UTF-8")
                    }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw failure
                } finally {
                    completed.set(true)
                    cancellation.cancel()
                }
            }
        }
}
