package com.bitchat.android.net.deploy

import java.io.*
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ServerPreparationTest {
    private fun digest(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun kitBytes(tampered: Boolean = false): ByteArray {
        val entries = PreparationKit.required.associateWith { ("synthetic:" + it).toByteArray() }
        val manifest =
            entries.map { (name, data) -> digest(data) + "  " + name }.joinToString("\n") + "\n"
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (entries + mapOf("deployer-SHA256SUMS" to manifest.toByteArray())).forEach {
                (name, bytes) ->
                zip.putNextEntry(ZipEntry("panchan-deployer/" + name))
                zip.write(if (tampered && name.endsWith("qwdtt-server")) byteArrayOf(1) else bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun `SSH key must match before password authentication can proceed`() {
        val key = ByteArray(32) { it.toByte() }
        val fingerprint =
            "SHA256:" +
                Base64.getEncoder()
                    .withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        var observed = ""
        assertEquals(
            com.jcraft.jsch.HostKeyRepository.CHANGED,
            PinnedHostKeys(null) { observed = it }.check("synthetic", key),
        )
        assertEquals(fingerprint, observed)
        assertEquals(
            com.jcraft.jsch.HostKeyRepository.OK,
            PinnedHostKeys(fingerprint).check("synthetic", key),
        )
        assertEquals(
            com.jcraft.jsch.HostKeyRepository.CHANGED,
            PinnedHostKeys(fingerprint).check("synthetic", ByteArray(32)),
        )
    }

    @Test
    fun `BC Ed25519 verifier is available for Android 10 without JDK15 APIs`() {
        val privateKey =
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(java.security.SecureRandom())
        val message = "synthetic SSH signature".toByteArray()
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        val signature = signer.generateSignature()
        val verifier = com.jcraft.jsch.bc.SignatureEd25519()
        verifier.init()
        verifier.setPubKey(privateKey.generatePublicKey().encoded)
        verifier.update(message)
        val wire=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(wire).use {
            val type="ssh-ed25519".toByteArray()
            it.writeInt(type.size);it.write(type);it.writeInt(signature.size);it.write(signature)
        }
        assertTrue(verifier.verify(wire.toByteArray()))
    }

    @Test
    fun `unsafe hosts ports and malformed pins are rejected`() {
        for (host in
            listOf("-option", "host;id", "root@host", "a..b", "host/command")) assertThrows(
            IllegalArgumentException::class.java
        ) {
            PreparationTarget(host, 22)
        }
        assertThrows(IllegalArgumentException::class.java) { PreparationTarget("192.0.2.1", 0) }
        assertThrows(IllegalArgumentException::class.java) {
            PreparationTarget("192.0.2.1", 22, "invalid")
        }
        assertFalse(PreparationTarget("192.0.2.1", 22).toString().contains("192.0.2.1"))
    }

    @Test
    fun `verified kit stages and activates qwdtt after admission readiness`() = runTest {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val data = kitBytes()
            val kit = PreparationKit.read(data.inputStream(), digest(data), directory)
            val fake = FakeConnection()
            val progress = mutableListOf<String>()
            ServerPreparation.run(fake, kit) { progress += it }
            assertEquals(3, fake.uploaded.size)
            assertEquals("qwdtt-server", fake.uploaded[0])
            assertEquals(5, fake.commands.size)
            assertTrue(fake.commands[2].startsWith("bash -s -- "))
            assertFalse(
                fake.commands.any {
                    it.contains("systemctl start qwdtt") || it.contains("systemctl start cups")
                }
            )
            assertTrue(fake.input.contains("sha256sum -c"))
            assertTrue(progress.size >= 6)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `tampered kit and wrong OS stop before upload`() = runTest {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val bad = kitBytes(true)
            try {
                PreparationKit.read(bad.inputStream(), digest(bad), directory)
                fail("tampered entry accepted")
            } catch (_: IllegalStateException) {}
            val data = kitBytes()
            val kit = PreparationKit.read(data.inputStream(), digest(data), directory)
            val fake = FakeConnection("OS=unknown\nARCH=x86_64\nUID=0\nFREE_KB=999999\n")
            assertThrows(IllegalStateException::class.java) { ServerPreparation.run(fake, kit) {} }
            assertTrue(fake.uploaded.isEmpty())
            assertEquals(1, fake.commands.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `insufficient privileges and capacity cannot run installer`() {
        for (response in
            listOf(
                "OS=debian\nARCH=x86_64\nUID=1000\nFREE_KB=999999\n",
                "OS=debian\nARCH=x86_64\nUID=0\nFREE_KB=10\n",
            )) assertThrows(IllegalStateException::class.java) {
            ServerPreparation.validateInventory(response)
        }
    }

    private class FakeConnection(
        private val inventory: String = "OS=debian\nARCH=x86_64\nUID=0\nFREE_KB=999999\n"
    ) : PreparationConnection {
        val commands = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        var input = ""

        override fun connect(target: PreparationTarget, password: ByteArray) {}

        override fun upload(file: File, name: String) {
            uploaded += name
        }

        override fun execute(command: String, input: ByteArray): String {
            commands += command
            if (input.isNotEmpty()) this.input += input.toString(Charsets.UTF_8)
            return if (command == ServerPreparation.inventoryCommand) inventory
            else if (command.endsWith(" status")) "{\"version\":1}"
            else if (command.startsWith("bash -s -- ") && commands.count { it.startsWith("bash -s -- ") } == 2) "QWDTT_READY\n" else ""
        }

        override fun close() {}
    }
}
