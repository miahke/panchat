package com.bitchat.android.net.route

import org.junit.Assert.*
import org.junit.Test

class QwdttTelemetryTest {
    @Test
    fun `only bounded numeric records are accepted`() {
        val value = parseQwdttStatistics("STATS:2:1234:5678")
        assertEquals(2, value.workers)
        assertEquals(5678L, value.downBytes)
        for (line in
            listOf(
                "STATS:3:0:0",
                "STATS:1:-1:0",
                "STATS:1:secret:0",
                "STATS:1:0:0:extra",
                "STATS:1:9999999999999999999:0",
            )) assertThrows(IllegalArgumentException::class.java) { parseQwdttStatistics(line) }
    }
}
