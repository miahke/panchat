package com.bitchat.android.net.vk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.net.route.NetworkRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** OAuth flow adapted from qWDTT, with exact redirect/state checks and no JavaScript bridge. */
class VkAuthActivity : ComponentActivity() {
    companion object {
        suspend fun clearTransientStorage() {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    CookieManager.getInstance().removeAllCookies {
                        WebStorage.getInstance().deleteAllData()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
            withContext(Dispatchers.IO) { CookieManager.getInstance().flush() }
        }
    }
    private lateinit var web: WebView
    private var accepting = false
    private var generation: Long? = null
    private val nonce = NetworkRuntime.randomPassword()
    private val flow=VkLoginFlow(nonce)
    private var loginAttempt=0
    private var loginCommitted=false
    private var prepared=false
    private val popups=mutableSetOf<WebView>()
    private var message by mutableStateOf("Вход VK для получения хэшей звонка")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptCanOpenWindowsAutomatically=false
            settings.setSupportMultipleWindows(true)
            webChromeClient=object:WebChromeClient(){
                override fun onCreateWindow(view:WebView,isDialog:Boolean,isUserGesture:Boolean,resultMsg:android.os.Message):Boolean{
                    if(!isUserGesture || popups.isNotEmpty())return false
                    val popup=WebView(this@VkAuthActivity)
                    popup.settings.allowFileAccess=false;popup.settings.allowContentAccess=false
                    popup.settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    popup.webViewClient=object:WebViewClient(){
                        override fun shouldOverrideUrlLoading(child:WebView,request:WebResourceRequest):Boolean{
                            val target=request.url.toString()
                            if(request.isForMainFrame){
                                if(!handleUrl(target) && VkOAuthParser.allowedNavigation(target))web.loadUrl(target)
                                child.stopLoading();popups.remove(child);child.destroy()
                            }
                            return true
                        }
                    }
                    popups+=popup
                    val transport=resultMsg.obj as? WebView.WebViewTransport ?: return false
                    transport.webView=popup;resultMsg.sendToTarget();return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handleUrl(request.url.toString()) || !VkOAuthParser.allowedNavigation(request.url.toString())
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (handleUrl(url) || !VkOAuthParser.allowedNavigation(url)) view.stopLoading()
                }
                override fun onPageFinished(view:WebView,url:String){
                    if(handleUrl(url))return
                    if(!VkOAuthParser.allowedNavigation(url))return
                    val hasSession=listOf("https://vk.com","https://vk.ru","https://m.vk.com","https://m.vk.ru").any { domain ->
                        CookieManager.getInstance().getCookie(domain).orEmpty().split(';').any { it.trim().startsWith("remixsid=") && it.trim().substringAfter('=').isNotEmpty() }
                    }
                    flow.afterPage(url,hasSession)?.let { target ->message="Подтвердите получение хэшей звонка";view.loadUrl(target) }
                }
                override fun doUpdateVisitedHistory(view:WebView,url:String,isReload:Boolean){handleUrl(url)}
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){
                    if(request.isForMainFrame)message="Страница VK не загрузилась. Повторите вход или выберите другой вариант."
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel(); message = "Ошибка сертификата VK. Соединение остановлено."
                }
            }
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Вход VK", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { finish() }) { Text("Закрыть") }
                        }
                        Text(message, Modifier.padding(horizontal = 12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onClick={if(prepared && !accepting)web.loadUrl(flow.loginUrl(loginAttempt))}){Text("Повторить")}
                            TextButton(onClick={
                                if(prepared && !accepting){loginAttempt=(loginAttempt+1)%3
                                    web.settings.userAgentString=if(loginAttempt==2)"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36" else WebSettings.getDefaultUserAgent(this@VkAuthActivity)
                                    message="Вход на официальной странице VK, вариант ${loginAttempt+1}/3"
                                    web.loadUrl(flow.loginUrl(loginAttempt))
                                }
                            }){Text("Другой вариант входа")}
                        }
                        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        lifecycleScope.launch {
            try {
                withTimeout(15_000){NetworkRuntime.loaded.first { it }}
                generation = NetworkRuntime.vkSession.generation
                CookieManager.getInstance().removeAllCookies {
                    if(!isFinishing && !isDestroyed){
                        WebStorage.getInstance().deleteAllData()
                        prepared=true;web.loadUrl(flow.loginUrl(loginAttempt))
                    }
                }
            }catch(_:kotlinx.coroutines.TimeoutCancellationException){message="Сетевые настройки ещё не готовы. Закройте окно и повторите вход."}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){message="Сетевые настройки ещё не готовы. Закройте окно и повторите вход."}
        }
        lifecycleScope.launch {
            while(isActive){
                if(prepared && !accepting && !flow.oauthRequested && hasWindowFocus()){
                    val current=web.url.orEmpty()
                    val loggedIn=listOf("https://vk.com","https://vk.ru","https://m.vk.com","https://m.vk.ru").any { domain ->
                        CookieManager.getInstance().getCookie(domain).orEmpty().split(';').any { it.trim().startsWith("remixsid=") && it.trim().substringAfter('=').isNotEmpty() }
                    }
                    flow.afterPage(current,loggedIn)?.let { message="Подтвердите получение хэшей звонка";web.loadUrl(it) }
                }
                delay(750)
            }
        }
        lifecycleScope.launch {
            val before = NetworkRuntime.wipeEpoch.value
            NetworkRuntime.wipeEpoch.first { it != before }
            finish()
        }
    }

    private fun handleUrl(url: String): Boolean {
        val token = VkOAuthParser.token(url, nonce) ?: return false
        if (!accepting) {
            accepting = true
            web.stopLoading()
            lifecycleScope.launch {
                try {
                    NetworkRuntime.vkSession.acceptToken(token, requireNotNull(generation))
                    loginCommitted=true
                    CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush();setResult(RESULT_OK);finish() }
                } catch (_: Exception) { message = "Не удалось сохранить сессию VK"; accepting = false }
            }
        }
        return true
    }

    override fun onDestroy() {
        if(!loginCommitted && NetworkRuntime.loaded.value)generation?.let { NetworkRuntime.vkSession.cancelSignIn(it) }
        popups.toList().forEach { it.stopLoading();it.destroy() };popups.clear()
        if (::web.isInitialized) {
            web.stopLoading(); web.clearCache(true); web.clearHistory(); web.destroy()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }
}
