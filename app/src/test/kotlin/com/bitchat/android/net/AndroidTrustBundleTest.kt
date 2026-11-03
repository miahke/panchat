package com.bitchat.android.net

import com.bitchat.android.net.bridge.AndroidTrustBundle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidTrustBundleTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `exports provided trusted issuers and never accepts an empty trust store`() {
        val store = KeyStore.getInstance("PKCS12").apply {
            AndroidTrustBundleTest::class.java.getResourceAsStream("/network/synthetic-relay.p12").use { load(it, "synthetic-test-password".toCharArray()) }
        }
        val cert = store.getCertificate(store.aliases().nextElement()) as X509Certificate
        val file = java.io.File(temporary.root, "nested/trust.pem")
        AndroidTrustBundle.write(file, listOf(cert))
        val parsed = file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        assertArrayEquals(cert.encoded, parsed.encoded)
        val builder = ProcessBuilder("synthetic-no-execution")
        AndroidTrustBundle.configureProcess(builder, temporary.root, listOf(cert))
        assertEquals(java.io.File(temporary.root, "android-trust.pem").absolutePath, builder.environment()["SSL_CERT_FILE"])
        assertTrue(java.io.File(builder.environment()["SSL_CERT_DIR"]!!).isDirectory)
        assertThrows(IllegalArgumentException::class.java) { AndroidTrustBundle.write(file, emptyList()) }
        assertArrayEquals(cert.encoded, file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }.encoded)
    }
}
