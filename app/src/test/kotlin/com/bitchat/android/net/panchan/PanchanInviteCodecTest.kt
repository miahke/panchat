package com.bitchat.android.net.panchan

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PanchanInviteCodecTest {
    private val now = 1800000000L
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun payload() = PanchanInvite(now, now + 3600, ByteArray(32) { it.toByte() }, PanchanTransport.QWDTT,
        "Synthetic exit", "qwdtt://config?peer=192.0.2.1&pass=synthetic-payload")
    @Test fun `signed invitation requires pinned issuer and round trips without logging credentials`() {
        val issuer = key()
        val link = PanchanInviteCodec.encode(payload(), issuer.private, issuer.public)
        val decoded = PanchanInviteCodec.decode(link, PanchanInviteCodec.fingerprint(issuer.public), now)
        assertEquals(payload().configuration, decoded.configuration)
        assertArrayEquals(payload().invitation, decoded.invitation)
        assertFalse(decoded.toString().contains("synthetic-payload"))
        assertFalse(link.contains("PRIVATE"))
    }
    @Test fun `tampering untrusted key and noncanonical base64 fail`() {
        val issuer = key()
        val link = PanchanInviteCodec.encode(payload(), issuer.private, issuer.public)
        val pin = PanchanInviteCodec.fingerprint(issuer.public)
        val parts = link.removePrefix("panchan://v1/").split('.').toMutableList()
        val data = Base64.getUrlDecoder().decode(parts[0]); data[data.lastIndex] = (data.last() + 1).toByte()
        parts[0] = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        for (bad in listOf("panchan://v1/" + parts.joinToString("."), link + "=", link + ".extra", "panchan://v2/" + link.substringAfter("panchan://v1/"))) {
            reject { PanchanInviteCodec.decode(bad, pin, now) }
        }
        reject { PanchanInviteCodec.decode(link, PanchanInviteCodec.fingerprint(key().public), now) }
        reject { PanchanInviteCodec.decode(link, "", now) }
    }
    @Test fun `expiry future issuance excessive lifetime and malformed invitations fail`() {
        val issuer = key(); val pin = PanchanInviteCodec.fingerprint(issuer.public)
        val link = PanchanInviteCodec.encode(payload(), issuer.private, issuer.public)
        reject { PanchanInviteCodec.decode(link, pin, now + 3600) }
        reject { PanchanInviteCodec.decode(link, pin, now - 301) }
        reject { PanchanInviteCodec.encode(payload().copy(expiresAt = now + 604801), issuer.private, issuer.public) }
        reject { PanchanInviteCodec.encode(payload().copy(invitation = ByteArray(31)), issuer.private, issuer.public) }
        reject { PanchanInviteCodec.encode(payload().copy(configuration = "x".repeat(32769)), issuer.private, issuer.public) }
        reject { PanchanInviteCodec.decode("panchan://v1/" + "x".repeat(50000), pin, now) }
    }
    @Test fun `non P256 issuer is rejected`() {
        val issuer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        reject { PanchanInviteCodec.encode(payload(), issuer.private, issuer.public) }
    }
    @Test fun `independent Go P256 vector verifies and decodes`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/network/panchan-invite-v1.json")).bufferedReader().use { it.readText() }
        val vector = com.google.gson.JsonParser.parseString(text).asJsonObject
        val decoded = PanchanInviteCodec.decode(vector["link"].asString, vector["fingerprint"].asString, now)
        assertEquals("Synthetic exit", decoded.name)
        assertEquals("openflux://v1/synthetic-vector", decoded.configuration)
        assertArrayEquals(ByteArray(32) { 0x51 }, decoded.invitation)
    }
    @Test fun `trusted signature does not permit invalid binary payloads`() {
        val issuer = key(); val pin = PanchanInviteCodec.fingerprint(issuer.public)
        val parts = PanchanInviteCodec.encode(payload(), issuer.private, issuer.public).removePrefix("panchan://v1/").split('.')
        val original = Base64.getUrlDecoder().decode(parts[0])
        val invalids = listOf(
            original.copyOf().apply { this[0] = 0 },
            original.copyOf().apply { this[52] = 99 },
            original.copyOf().apply { this[55] = 0xff.toByte() },
            original + byteArrayOf(0))
        for (bytes in invalids) {
            val signature = java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(issuer.private); update("PANCHAN-INVITE-v1\u0000".toByteArray(Charsets.US_ASCII)); update(bytes); sign()
            }
            val encode = Base64.getUrlEncoder().withoutPadding()
            val link = "panchan://v1/" + encode.encodeToString(bytes) + "." + parts[1] + "." + encode.encodeToString(signature)
            reject { PanchanInviteCodec.decode(link, pin, now) }
        }
    }
    private fun reject(action: () -> Unit) { try { action(); fail("Invalid invitation accepted") } catch (_: IllegalArgumentException) { } }
}
