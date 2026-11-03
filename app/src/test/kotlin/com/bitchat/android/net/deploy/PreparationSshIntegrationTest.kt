package com.bitchat.android.net.deploy

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Only an explicitly supplied, synthetic loopback sshd; never a physical device. */
class PreparationSshIntegrationTest {
    @Test
    fun `encrypted OpenSSH key authenticates with pin and rejects wrong passphrase`() =
        runBlocking {
            val port = System.getenv("PANCHAN_LOCAL_SSH_PORT")?.toIntOrNull()
            assumeTrue(port != null)
            val target =
                PreparationTarget(
                    "127.0.0.1",
                    port!!,
                    checkNotNull(System.getenv("PANCHAN_LOCAL_SSH_PIN")),
                    checkNotNull(System.getenv("PANCHAN_LOCAL_SSH_USER")),
                )
            val bytes = File(checkNotNull(System.getenv("PANCHAN_LOCAL_SSH_KEY"))).readBytes()
            try {
                var refused = false
                try {
                    withPreparationConnection {
                        it.connect(
                            target,
                            PreparationAuthentication.PrivateKey(
                                bytes.copyOf(),
                                "wrong-passphrase".toByteArray(),
                            ),
                        )
                    }
                } catch (_: Exception) {
                    refused = true
                }
                assertTrue(refused)
                withPreparationConnection {
                    it.connect(
                        target,
                        PreparationAuthentication.PrivateKey(
                            bytes.copyOf(),
                            "synthetic-passphrase".toByteArray(),
                        ),
                    )
                    assertEquals("0", it.execute("id -u").trim())
                }
                refused = false
                try {
                    withPreparationConnection {
                        it.connect(
                            PreparationTarget(
                                "127.0.0.1",
                                port,
                                "SHA256:" + "A".repeat(43),
                                target.username,
                            ),
                            PreparationAuthentication.PrivateKey(
                                bytes.copyOf(),
                                "synthetic-passphrase".toByteArray(),
                            ),
                        )
                    }
                } catch (_: Exception) {
                    refused = true
                }
                assertTrue(refused)
            } finally {
                bytes.fill(0)
            }
        }
}
