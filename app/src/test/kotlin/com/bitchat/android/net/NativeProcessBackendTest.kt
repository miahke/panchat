package com.bitchat.android.net

import com.bitchat.android.net.route.*
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeProcessBackendTest {
    private open class Child(reply: String) : Process() {
        val stdin = ByteArrayOutputStream()
        private val replyBytes = ByteArrayInputStream(reply.toByteArray())
        val stdout =
            object : InputStream() {
                override fun read(): Int {
                    val n = replyBytes.read()
                    if (n < 0) exit.await()
                    return n
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = replyBytes.read(b, off, len)
                    if (n < 0) exit.await()
                    return n
                }
            }
        val exit = CountDownLatch(1)
        var destroyed = false

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int {
            exit.await()
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exit.await(timeout, unit)

        override fun exitValue(): Int =
            if (exit.count == 0L) 0 else throw IllegalThreadStateException()

        override fun destroy() {
            destroyed = true
            exit.countDown()
        }
    }

    @Test(timeout = 5000)
    fun `lost status pipe revokes readiness even if child stays alive`() = runBlocking {
        val child =
            object : Child("READY\n") {
                val ended = ByteArrayInputStream("READY\n".toByteArray())

                override fun getInputStream(): InputStream = ended
            }
        val backend =
            NativeProcessBackend(
                File("/synthetic/core"),
                File("synthetic"),
                { "{}" },
                { "synthetic-password" },
                { _, _ -> child },
            )
        backend.start(NetworkProfile(id = "test", route = NetworkRoute.VK))
        withTimeout(2000) { backend.state.first { it == BackendState.ERROR } }
        assertTrue(child.destroyed)
        backend.stop()
    }

    @Test
    fun `sends configuration through pipe and stops child when disconnected`() = runTest {
        val child = Child("READY\n")
        val backend =
            NativeProcessBackend(
                File("/synthetic/libtest.so"),
                File("synthetic"),
                { "{\"secret\":\"synthetic-password\"}" },
                { "synthetic-password" },
                { _, _ -> child },
            )
        val endpoint = backend.start(NetworkProfile(id = "test", route = NetworkRoute.VK))
        assertTrue(child.stdin.toString().contains("synthetic-password"))
        assertEquals("synthetic-password", endpoint.password)
        backend.stop()
        assertTrue(child.destroyed)
        assertEquals(BackendState.STOPPED, backend.state.value)
    }

    @Test
    fun `structured native stages are shown without treating them as readiness`() = runTest {
        NetworkDiagnostics.log.clear()
        val child =
            Child(
                "STAGE:TURN_ALLOCATING\nSTAGE:TURN_READY\nSTAGE:AUTHENTICATING\nSTAGE:RAW_HANDSHAKE_RETRY\nREADY\n"
            )
        val backend =
            NativeProcessBackend(
                File("/synthetic/libtest.so"),
                File("synthetic"),
                { "{}" },
                { "synthetic-password" },
                { _, _ -> child },
            )
        backend.start(NetworkProfile(id = "synthetic-status", route = NetworkRoute.VK))
        val stages = NetworkDiagnostics.log.events.value.map { it.stage }
        assertTrue(stages.contains(NetworkStage.TURN_ALLOCATING))
        assertTrue(stages.contains(NetworkStage.AUTHENTICATING))
        assertTrue(stages.contains(NetworkStage.RAW_HANDSHAKE_RETRY))
        assertEquals(BackendState.READY, backend.state.value)
        backend.stop()
    }

    @Test(timeout = 7000)
    fun `status stream is drained after ready so worker writes cannot stall`() = runBlocking {
        val pipe = PipedInputStream(1024)
        val output = PipedOutputStream(pipe)
        val child =
            object : Child("") {
                override fun getInputStream(): InputStream = pipe

                override fun destroy() {
                    runCatching { output.close() }
                    super.destroy()
                }
            }
        val backend =
            NativeProcessBackend(
                File("/synthetic/libtest.so"),
                File("synthetic"),
                { "{}" },
                { "synthetic-password" },
                { _, _ -> child },
            )
        val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            output.write("READY\n".toByteArray())
            backend.start(NetworkProfile(id = "synthetic-status-drain", route = NetworkRoute.VK))
            val result =
                writer.submit<Boolean> {
                    output.write("STAGE:TURN_READY\n".repeat(6000).toByteArray())
                    output.flush()
                    true
                }
            assertTrue(result.get(3, TimeUnit.SECONDS))
        } finally {
            backend.stop()
            output.close()
            pipe.close()
            writer.shutdownNow()
        }
    }

    @Test
    fun `native error is redacted and child cleaned before propagating failure`() = runTest {
        val child = Child("ERROR synthetic-private-detail\n")
        val backend =
            NativeProcessBackend(
                File("/synthetic/libtest.so"),
                File("synthetic"),
                { "{}" },
                { "synthetic-password" },
                { _, _ -> child },
            )
        try {
            backend.start(NetworkProfile(id = "test", route = NetworkRoute.VK))
            fail("must fail")
        } catch (e: IOException) {
            assertFalse(e.message.orEmpty().contains("synthetic-private-detail"))
        }
        assertTrue(child.destroyed)
        assertEquals(BackendState.ERROR, backend.state.value)
    }

    @Test(timeout = 5000)
    fun `cancel during native startup terminates a silent child promptly`() = runBlocking {
        val pipe = PipedInputStream()
        val writer = PipedOutputStream(pipe)
        val reading = CountDownLatch(1)
        val child =
            object : Child("") {
                override fun getInputStream(): InputStream {
                    reading.countDown()
                    return pipe
                }

                override fun destroy() {
                    writer.close()
                    super.destroy()
                }

                override fun destroyForcibly(): Process {
                    destroy()
                    return this
                }
            }
        val fallback = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        try {
            fallback.schedule({ writer.close() }, 2500, TimeUnit.MILLISECONDS)
            val backend =
                NativeProcessBackend(
                    File("/synthetic/libtest.so"),
                    File("synthetic"),
                    { "{}" },
                    { "synthetic-password" },
                    { _, _ -> child },
                )
            val job =
                launch(Dispatchers.Default) {
                    backend.start(NetworkProfile(id = "test", route = NetworkRoute.VK))
                }
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(
                "startup cancellation blocked on native stdout",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500,
            )
            assertTrue(child.destroyed)
        } finally {
            writer.close()
            fallback.shutdownNow()
        }
    }
}
