package com.bitchat.android.security.vault

import android.util.AtomicFile
import java.io.File
import java.io.IOException

internal interface VaultRecordStore {
    fun read(): ByteArray?
    fun write(record: ByteArray)
}

/** Use under noBackupFilesDir. AtomicFile keeps the old envelope on an interrupted update. */
internal class AtomicVaultRecordStore(file: File) : VaultRecordStore {
    private val atomic = AtomicFile(file)

    override fun read(): ByteArray? {
        val base = atomic.baseFile
        if (!base.exists() && !File(base.path + ".bak").exists()) return null
        return atomic.openRead().use { input ->
            val bytes = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                if (n == 0) throw IOException("Could not read vault state")
                count += n
            }
            if (count !in 1..MAX_BYTES) throw IOException("Invalid vault state size")
            bytes.copyOf(count)
        }
    }

    override fun write(record: ByteArray) {
        require(record.size in 1..MAX_BYTES)
        val output = atomic.startWrite()
        try {
            output.write(record)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private companion object { const val MAX_BYTES = 4125 }
}
