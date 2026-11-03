package com.bitchat.android.net.deploy

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit, opt-in owner's test VPS only. No account, message or location inspection. */
class ServerPreparationLiveTest {
    @Test
    fun `pinned SSH Android preparation path runs on authorized test VPS`() = runBlocking {
        val host = System.getenv("PANCHAN_TEST_VPS").orEmpty()
        assumeTrue("No authorized VPS supplied", host.isNotEmpty())
        val pin = checkNotNull(System.getenv("PANCHAN_TEST_SSH_PIN"))
        val password = checkNotNull(System.getenv("PANCHAN_TEST_SSH_PASSWORD")).toByteArray()
        val target = PreparationTarget(host, 22, pin)
        val root = File("src/main/assets/network")
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val observed = withPreparationConnection { it.probe(target) }
            assertEquals("Observed host key differs from owner pin", pin, observed)
            var rejected = false
            try {
                withPreparationConnection { connection ->
                    connection.connect(
                        PreparationTarget(host, 22, "SHA256:" + "A".repeat(43)),
                        password,
                    )
                }
            } catch (_: Exception) {
                rejected = true
            }
            assertTrue("Changed SSH key was accepted", rejected)
            withPreparationConnection { connection ->
                connection.connect(target, password)
                val kit =
                    File(root, "panchan-deployer.zip").inputStream().use {
                        PreparationKit.read(
                            it,
                            File(root, "panchan-deployer.sha256").readText().trim(),
                            directory,
                        )
                    }
                ServerPreparation.run(connection, kit) {}
            }
        } finally {
            password.fill(0)
            directory.deleteRecursively()
        }
    }
}
