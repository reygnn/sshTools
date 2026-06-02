package com.github.reygnn.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pure unit tests for [readCapped]'s memory-bounding contract. The cap behaviour
 * is deterministic and provider-free, so it is pinned here rather than over a
 * live SSH channel (where closing the stream mid-transfer races the teardown).
 */
class StreamUtilsTest {

    @Test
    fun `reads the whole stream when under the cap`() {
        val input = ByteArrayInputStream("hello".toByteArray())
        assertEquals("hello", input.readCapped(1024))
    }

    @Test
    fun `truncates to exactly the cap when the stream is larger`() {
        val data = "x".repeat(10_000).toByteArray()
        val result = ByteArrayInputStream(data).readCapped(4096)
        assertEquals(4096, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `an empty stream yields an empty string`() {
        assertEquals("", ByteArrayInputStream(ByteArray(0)).readCapped(1024))
    }

    @Test
    fun `a zero cap reads nothing`() {
        assertEquals("", ByteArrayInputStream("data".toByteArray()).readCapped(0))
    }
}
