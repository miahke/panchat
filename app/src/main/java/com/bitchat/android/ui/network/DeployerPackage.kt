package com.bitchat.android.ui.network

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object DeployerPackage {
    suspend fun copyVerified(
        input: InputStream,
        output: OutputStream,
        expectedSha256: String,
        maximumBytes: Long = 128L * 1024 * 1024,
    ) {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            check(n > 0)
            total += n
            check(total <= maximumBytes) { "Package is too large" }
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
        }
        currentCoroutineContext().ensureActive()
        output.flush()
        check(
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expectedSha256
        ) {
            "Package checksum mismatch"
        }
    }
}
