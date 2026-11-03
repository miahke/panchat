package com.bitchat.android.net

import com.bitchat.android.net.vk.readVkResponse
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class VkResponseLimitTest {
    @Test fun `bounded response reader accepts limit and rejects extra byte`() {
        assertEquals(65536, readVkResponse(ByteArrayInputStream(ByteArray(65536))).size)
        assertThrows(java.io.IOException::class.java) { readVkResponse(ByteArrayInputStream(ByteArray(65537))) }
    }
}
