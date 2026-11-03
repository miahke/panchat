package com.bitchat.android.net.deploy

import com.jcraft.jsch.*
import java.io.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

class PreparationTarget(
    val host: String,
    val port: Int,
    val fingerprint: String = "",
    val username: String = "root",
) {
    init {
        require(host.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")) && ".." !in host)
        require(username.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,63}")))
        require(port in 1..65535)
        require(fingerprint.isEmpty() || fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
    }

    override fun toString() = "PreparationTarget([redacted])"
}

/** Rejects an unpinned key before SSH can send a password. Probe never authenticates. */
class PinnedHostKeys(private val expected: String?, private val observed: (String) -> Unit = {}) :
    HostKeyRepository {
    override fun check(host: String?, key: ByteArray): Int {
        val fingerprint =
            "SHA256:" +
                Base64.getEncoder()
                    .withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        observed(fingerprint)
        return if (
            expected != null &&
                MessageDigest.isEqual(expected.toByteArray(), fingerprint.toByteArray())
        )
            HostKeyRepository.OK
        else HostKeyRepository.CHANGED
    }

    override fun add(key: HostKey?, ui: UserInfo?) {
        error("Host key enrollment is explicit")
    }

    override fun remove(host: String?, type: String?) {}

    override fun remove(host: String?, type: String?, key: ByteArray?) {}

    override fun getKnownHostsRepositoryID() = "memory-only-pin"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

interface PreparationConnection : AutoCloseable {
    fun connect(target: PreparationTarget, password: ByteArray)

    fun execute(command: String, input: ByteArray = byteArrayOf()): String

    fun upload(file: File, name: String)
}

class JschPreparationConnection : PreparationConnection {
    companion object {
        init {
            // KeyPair signing uses JSch globals, not Session config. Select the
            // BC implementations also on Android 10, which lacks JDK EdEC APIs.
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        }
    }

    private val active = AtomicReference<Session?>()
    private var job: Job? = null
    private var identities: JSch? = null
    private var elevated = false
    private var uploadDirectory: String? = null

    fun bindJob(value: Job) {
        job = value
    }

    private fun session(target: PreparationTarget, keys: HostKeyRepository): Session {
        val jsch = JSch()
        identities = jsch
        jsch.hostKeyRepository = keys
        return jsch.getSession(target.username, target.host, target.port).also {
            it.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            it.setConfig("StrictHostKeyChecking", "yes")
            it.setConfig("PreferredAuthentications", "password")
            it.setConfig("NumberOfPasswordPrompts", "1")
            it.serverAliveInterval = 15_000
            it.serverAliveCountMax = 2
            it.timeout = 30_000
        }
    }

    fun probe(target: PreparationTarget): String {
        var pin: String? = null
        val session = session(target, PinnedHostKeys(null) { pin = it })
        active.set(session)
        try {
            session.connect(15_000)
        } catch (_: JSchException) {
            job?.ensureActive()
        } finally {
            close()
        }
        return checkNotNull(pin) { "SSH host key unavailable" }
    }

    override fun connect(target: PreparationTarget, password: ByteArray) =
        connect(target, PreparationAuthentication.Password(password.copyOf()))

    fun connect(target: PreparationTarget, authentication: PreparationAuthentication) {
        authentication.use { connectOwned(target, it) }
    }

    private fun connectOwned(target: PreparationTarget, authentication: PreparationAuthentication) {
        require(target.fingerprint.isNotEmpty())
        job?.ensureActive()
        elevated = false
        val session = session(target, PinnedHostKeys(target.fingerprint))
        active.set(session)
        try {
            session.setConfig("PreferredAuthentications", authentication.methods)
            when (authentication) {
                is PreparationAuthentication.Password -> session.setPassword(authentication.bytes)
                is PreparationAuthentication.PrivateKey -> {
                    checkNotNull(identities)
                        .addIdentity(
                            "memory-only-deploy",
                            authentication.bytes,
                            null,
                            authentication.passphrase,
                        )
                }
            }
            session.connect(15_000)
            job?.ensureActive()
            if (execute("id -u").trim() != "0") {
                check(execute("sudo -n -- id -u").trim() == "0") {
                    "Root or passwordless sudo required"
                }
                elevated = true
            }
        } catch (e: Exception) {
            close()
            throw e
        } finally {
            authentication.close()
        }
    }

    override fun execute(command: String, input: ByteArray): String {
        job?.ensureActive()
        val channel = checkNotNull(active.get()).openChannel("exec") as ChannelExec
        val stdout = BoundedOutput()
        val stderr = BoundedOutput()
        try {
            channel.setCommand(
                if (elevated) "sudo -n -- sh -c " + quoteSshCommand(command) else command
            )
            channel.setInputStream(ByteArrayInputStream(input))
            channel.setOutputStream(stdout)
            channel.setErrStream(stderr)
            channel.connect(15_000)
            val deadline = System.nanoTime() + 120_000_000_000L
            while (!channel.isClosed) {
                job?.ensureActive()
                check(System.nanoTime() < deadline) { "SSH step timed out" }
                check(!stdout.overflow && !stderr.overflow) { "SSH response too large" }
                Thread.sleep(25)
            }
            job?.ensureActive()
            check(!stdout.overflow && !stderr.overflow && channel.exitStatus == 0) {
                "SSH step failed"
            }
            return stdout.text()
        } finally {
            channel.disconnect()
        }
    }

    override fun upload(file: File, name: String) {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}")))
        job?.ensureActive()
        if (elevated && uploadDirectory == null) {
            val directory = "/tmp/panchan-upload-" + java.util.UUID.randomUUID().toString()
            execute(
                "install -d -o " +
                    quoteSshCommand(checkNotNull(active.get()).userName) +
                    " -m 700 -- " +
                    quoteSshCommand(directory)
            )
            uploadDirectory = directory
        }
        val channel = checkNotNull(active.get()).openChannel("sftp") as ChannelSftp
        try {
            channel.connect(15_000)
            channel.cd(uploadDirectory ?: "/opt/panchan/staging")
            file.inputStream().use { input ->
                channel.put(
                    input,
                    name,
                    object : SftpProgressMonitor {
                        override fun init(op: Int, src: String?, dest: String?, max: Long) {}

                        override fun count(count: Long): Boolean {
                            job?.ensureActive()
                            return true
                        }

                        override fun end() {}
                    },
                    ChannelSftp.OVERWRITE,
                )
            }
            channel.chmod(0b110000000, name)
            if (elevated) {
                val source = checkNotNull(uploadDirectory) + "/" + name
                execute(
                    "install -m 600 -- " +
                        quoteSshCommand(source) +
                        " " +
                        quoteSshCommand("/opt/panchan/staging/" + name)
                )
                channel.rm(name)
            }
        } finally {
            channel.disconnect()
        }
    }

    override fun close() {
        active.getAndSet(null)?.let { session ->
            // Only public, checksum-verified kit bytes ever use this directory.
            uploadDirectory?.let { directory ->
                runCatching {
                    val channel = session.openChannel("sftp") as ChannelSftp
                    try {
                        channel.connect(2000)
                        channel.rmdir(directory)
                    } finally {
                        channel.disconnect()
                    }
                }
            }
            session.disconnect()
        }
        uploadDirectory = null
        runCatching { identities?.removeAllIdentity() }
        identities = null
        elevated = false
    }

    private class BoundedOutput : OutputStream() {
        private val data = ByteArrayOutputStream()
        @Volatile var overflow = false

        @Synchronized
        override fun write(b: Int) {
            if (data.size() < 32768) data.write(b) else overflow = true
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            val remaining = 32768 - data.size()
            data.write(b, off, minOf(len, remaining))
            if (len > remaining) overflow = true
        }

        @Synchronized fun text() = data.toString("UTF-8")
    }
}

/** All calls execute on IO; cancellation closes SSH to unblock reads/uploads. */
suspend fun <T> withPreparationConnection(block: suspend (JschPreparationConnection) -> T): T =
    withTimeout(600_000) {
        coroutineScope {
            val connection =
                JschPreparationConnection().also { it.bindJob(currentCoroutineContext().job) }
            val closer =
                launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.close()
                    }
                }
            try {
                withContext(Dispatchers.IO) { block(connection) }
            } finally {
                connection.close()
                closer.cancelAndJoin()
            }
        }
    }
