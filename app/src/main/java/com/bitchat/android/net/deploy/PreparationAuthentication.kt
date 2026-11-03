package com.bitchat.android.net.deploy

/** Secrets are owned by this single attempt, never serialized or kept in saved state. */
sealed class PreparationAuthentication(val methods: String) : AutoCloseable {
    class Password(val bytes: ByteArray) : PreparationAuthentication("password") {
        init {
            require(bytes.size in 1..4096 && bytes.none { it == 0.toByte() })
        }

        override fun close() {
            bytes.fill(0)
        }
    }

    class PrivateKey(val bytes: ByteArray, val passphrase: ByteArray = byteArrayOf()) :
        PreparationAuthentication("publickey") {
        init {
            require(bytes.size in 1..262144 && passphrase.size <= 4096)
        }

        override fun close() {
            bytes.fill(0)
            passphrase.fill(0)
        }
    }

    final override fun toString() = "PreparationAuthentication([redacted])"
}

internal fun quoteSshCommand(value: String) = "'" + value.replace("'", "'\\''") + "'"

internal fun readPreparationPrivateKey(input: java.io.InputStream): ByteArray {
    val buffer = ByteArray(262145)
    try {
        var size = 0
        while (size < buffer.size) {
            val n = input.read(buffer, size, buffer.size - size)
            if (n < 0) break
            require(n > 0)
            size += n
        }
        require(size in 1..262144) { "Private key file is empty or too large" }
        return buffer.copyOf(size)
    } finally {
        buffer.fill(0)
    }
}
