package com.bitchat.android.net
import com.bitchat.android.net.vk.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
class VkLoginFlowTest {
 @Test fun `login precedes OAuth and a browser session triggers exactly one nonce-bound request`(){
  val flow=VkLoginFlow("synthetic-nonce")
  assertEquals("https://m.vk.ru/login",flow.loginUrl(0))
  assertNull(flow.afterPage("https://m.vk.ru/login",false))
  assertNull(flow.afterPage("https://evil.invalid/",true))
  val oauth=checkNotNull(flow.afterPage("https://m.vk.ru/",true))
  assertEquals("oauth.vk.com",URI(oauth).host)
  assertTrue(oauth.contains("state=synthetic-nonce"))
  assertNull(flow.afterPage("https://m.vk.ru/",true))
  assertEquals("https://vk.ru/login",flow.loginUrl(2))
  assertNotNull(flow.afterPage("https://vk.ru/",true))
 }
}
