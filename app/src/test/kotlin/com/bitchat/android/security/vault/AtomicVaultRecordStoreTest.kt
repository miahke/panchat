package com.bitchat.android.security.vault

import android.util.AtomicFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AtomicVaultRecordStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `durable record reopens and interrupted replacement restores previous state`() {
        val file = java.io.File(temporary.root, "vault-state")
        val store = AtomicVaultRecordStore(file)
        assertNull(store.read())
        val committed = byteArrayOf(1, 2, 3)
        store.write(committed)
        assertArrayEquals(committed, AtomicVaultRecordStore(file).read())
        val unfinished = AtomicFile(file).startWrite()
        unfinished.write(byteArrayOf(9, 9))
        unfinished.close() // process dies without finishWrite
        assertArrayEquals(committed, AtomicVaultRecordStore(file).read())
    }

    @Test fun `oversized and empty durable records are errors rather than a new vault`() {
        val file = temporary.newFile("vault-state")
        val store = AtomicVaultRecordStore(file)
        assertThrows(IOException::class.java) { store.read() }
        file.writeBytes(ByteArray(4126))
        assertThrows(IOException::class.java) { store.read() }
    }
}
