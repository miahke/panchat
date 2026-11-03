package com.bitchat.android.net

import com.bitchat.android.net.bridge.BridgeParser
import com.bitchat.android.net.bridge.BridgeProtocol
import org.junit.Assert.*
import org.junit.Test

class BridgeParserTest {
    private val fingerprint = "0123456789ABCDEF0123456789ABCDEF01234567"
    private val cert = java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52) { 1 })

    @Test fun `imports user obfs4 and webtunnel lines preserving options`() {
        val bridges = BridgeParser.parse("""
            Bridge obfs4 192.0.2.1:443 $fingerprint cert=$cert iat-mode=0
            webtunnel [2001:db8::1]:443 $fingerprint url=https://bridge.example.invalid/secret ver=0.0.3
        """.trimIndent())
        assertEquals(2, bridges.size)
        assertEquals(BridgeProtocol.OBFS4, bridges[0].protocol)
        assertEquals(BridgeProtocol.WEBTUNNEL, bridges[1].protocol)
        assertEquals("https://bridge.example.invalid/secret", bridges[1].options["url"])
        assertFalse(bridges.toString().contains(cert))
        assertFalse(bridges.toString().contains("/secret"))
    }

    @Test fun `rejects malformed or unprotected bridge transport settings`() {
        val lines = listOf(
            "obfs4 192.0.2.1:0 $fingerprint cert=$cert iat-mode=0",
            "obfs4 192.0.2.1:443 invalid cert=$cert iat-mode=0",
            "obfs4 192.0.2.1:443 $fingerprint cert=invalid iat-mode=0",
            "obfs4 192.0.2.1:443 $fingerprint cert=$cert iat-mode=3",
            "obfs4 192.0.2.1:443 $fingerprint cert=$cert cert=$cert iat-mode=0",
            "webtunnel 192.0.2.1:443 $fingerprint url=http://bridge.example.invalid/path",
            "webtunnel 192.0.2.1:443 $fingerprint url=https://user:password@bridge.example.invalid/path",
            "exec 192.0.2.1:443 $fingerprint path=/tmp/anything",
            "obfs4 192.0.2.1:443 $fingerprint cert=$cert iat-mode=0;command",
        )
        for (line in lines) {
            val failure = assertThrows(IllegalArgumentException::class.java) { BridgeParser.parse(line) }
            assertFalse(failure.message.orEmpty().contains(cert))
        }
    }

    @Test fun `validation reports physical line without echoing bridge contents`() {
        val failure = assertThrows(com.bitchat.android.net.bridge.BridgeValidationException::class.java) {
            BridgeParser.parse("# synthetic comment\n\nwebtunnel synthetic-private-bridge-data")
        }
        assertEquals(3, failure.lineNumber)
        assertFalse(failure.message.orEmpty().contains("synthetic-private"))
    }

    @Test fun `accepts ordinary numeric bridge but never resolves hostnames during parsing`() {
        assertEquals(BridgeProtocol.PLAIN, BridgeParser.parse("192.0.2.1:443 $fingerprint").single().protocol)
        assertThrows(IllegalArgumentException::class.java) {
            BridgeParser.parse("bridge.example.invalid:443 $fingerprint")
        }
    }
}
