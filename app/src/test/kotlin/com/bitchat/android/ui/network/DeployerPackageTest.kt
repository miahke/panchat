package com.bitchat.android.ui.network

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeployerPackageTest {
    private fun hash(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }

    @Test
    fun `export copies verified bytes and rejects modified data`() = runTest {
        val data = "synthetic server package".toByteArray()
        val output = ByteArrayOutputStream()
        DeployerPackage.copyVerified(data.inputStream(), output, hash(data))
        assertArrayEquals(data, output.toByteArray())
        try {
            DeployerPackage.copyVerified(
                "modified".byteInputStream(),
                ByteArrayOutputStream(),
                hash(data),
            )
            fail("Modified package accepted")
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun `export bounds size and responds to cancellation while writing`() = runTest {
        val data = ByteArray(262144) { 1 }
        var written = 0
        val job = Job()
        val output =
            object : OutputStream() {
                override fun write(value: Int) {
                    written++
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    written += length
                    job.cancel()
                }
            }
        try {
            withContext(job) {
                DeployerPackage.copyVerified(data.inputStream(), output, hash(data))
            }
            fail("Cancellation ignored")
        } catch (_: CancellationException) {}
        assertTrue(written < data.size)
        try {
            DeployerPackage.copyVerified(
                data.inputStream(),
                ByteArrayOutputStream(),
                hash(data),
                maximumBytes = 128,
            )
            fail("Oversized package accepted")
        } catch (_: IllegalStateException) {}
    }
}
