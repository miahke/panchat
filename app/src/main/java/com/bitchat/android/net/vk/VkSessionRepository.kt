package com.bitchat.android.net.vk

import com.bitchat.android.net.route.NetworkSecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VkSessionException(val publicMessage:String):IllegalStateException(publicMessage)

/** The token is scoped to this module, never Bitchat identity, logs or URL parameters. */
class VkSessionRepository(
    private val secrets:NetworkSecretStore,
    private val client:OkHttpClient=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build(),
    private val callFactory:(OkHttpClient,Request)->Call={http,request->http.newCall(request)},
) {
    private val sessionLock=Any()
    private val revision=java.util.concurrent.atomic.AtomicLong(0)
    val generation:Long get()=revision.get()
    private val tokenRef="vk-session:access-token"
    private val mutableLoggedIn=MutableStateFlow(secrets.get(tokenRef)!=null)
    val loggedIn=mutableLoggedIn.asStateFlow()
    private val activeCalls=mutableSetOf<Call>()

    suspend fun acceptToken(token:String,expectedGeneration:Long)=withContext(Dispatchers.IO){
        require(token.length in 1..8192 && token.none(Char::isWhitespace))
        currentCoroutineContext().ensureActive()
        synchronized(sessionLock){
            check(revision.get()==expectedGeneration){"VK sign-in was cancelled"}
            secrets.put(tokenRef,token)
            revision.incrementAndGet()
            mutableLoggedIn.value=true
        }
    }
    fun cancelSignIn(expectedGeneration:Long)=synchronized(sessionLock){
        if(revision.get()==expectedGeneration)revision.incrementAndGet()
    }
    suspend fun logout()=withContext(Dispatchers.IO){
        val calls=synchronized(sessionLock){
            revision.incrementAndGet();secrets.remove(tokenRef);mutableLoggedIn.value=false
            activeCalls.toList()
        }
        calls.forEach { it.cancel() }
    }
    suspend fun generateCallHash():String=withContext(Dispatchers.IO){
        val (token,epoch)=synchronized(sessionLock){
            val token=secrets.get(tokenRef) ?: throw VkSessionException("Сначала войдите в VK.")
            token to revision.get()
        }
        val body=FormBody.Builder().add("access_token",token).add("v","5.199").build()
        val request=Request.Builder().url("https://api.vk.ru/method/calls.start").post(body).build()
        val call=callFactory(client,request)
        synchronized(sessionLock){check(revision.get()==epoch){"VK session changed"};activeCalls+=call}
        try {
            coroutineScope {
                val completed=java.util.concurrent.atomic.AtomicBoolean(false)
                val cancel=launch(start=CoroutineStart.UNDISPATCHED){try{awaitCancellation()}finally{if(!completed.get())call.cancel()}}
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful){"VK request failed"}
                        val bytes=readVkResponse(requireNotNull(response.body).byteStream())
                        val json=try{JSONObject(bytes.toString(Charsets.UTF_8))}finally{bytes.fill(0)}
                        json.optJSONObject("error")?.let { error ->
                            val code=error.optInt("error_code")
                            if(code==5)synchronized(sessionLock){
                                if(revision.get()==epoch){secrets.remove(tokenRef);revision.incrementAndGet();mutableLoggedIn.value=false}
                            }
                            throw VkSessionException(when(code){
                                5->"Сессия VK истекла. Выполните вход заново."
                                6,9,29->"VK ограничил частоту запросов. Подождите и повторите получение хэша."
                                14->"VK запросил подтверждение. Повторите вход или вставьте готовый хэш."
                                7,15->"VK не разрешил создание звонка для этой сессии. Повторите вход или используйте готовый хэш."
                                else->"VK не выдал ссылку звонка. Повторите позже или вставьте хэш вручную."
                            })
                        }
                        val hash=VkOAuthParser.callHash(json.getJSONObject("response").getString("join_link"))
                        currentCoroutineContext().ensureActive()
                        synchronized(sessionLock){check(revision.get()==epoch && mutableLoggedIn.value){"VK session changed"}}
                        hash
                    }
                }catch(failure:Exception){currentCoroutineContext().ensureActive();throw failure}
                finally{completed.set(true);cancel.cancel()}
            }
        }finally{synchronized(sessionLock){activeCalls.remove(call)}}
    }
}

internal fun readVkResponse(stream:java.io.InputStream):ByteArray{
    val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
    while(true){val count=stream.read(buffer,0,minOf(buffer.size,65537-result.size()));if(count==-1)return result.toByteArray()
        if(count==0)throw java.io.IOException("VK response made no progress")
        result.write(buffer,0,count);if(result.size()>65536)throw java.io.IOException("VK response is too large")}
}
