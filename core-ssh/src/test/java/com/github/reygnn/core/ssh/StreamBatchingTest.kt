package com.github.reygnn.core.ssh

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBatchingTest {

    @Test
    fun `items emitted together arrive as one batch, order preserved`() = runTest {
        // All items are emitted before any window elapses, so they flush together
        // as the completion tail.
        val batches = flowOf(1, 2, 3, 4, 5).chunkedByTime(50).toList()
        assertEquals(listOf(listOf(1, 2, 3, 4, 5)), batches)
    }

    @Test
    fun `items separated by more than the window land in separate batches`() = runTest {
        val source = flow {
            emit(1)
            delay(60) // the 50ms ticker flushes [1] before this resumes
            emit(2)
        }
        assertEquals(listOf(listOf(1), listOf(2)), source.chunkedByTime(50).toList())
    }

    @Test
    fun `the final item is always flushed as the tail`() = runTest {
        // A trailing marker (e.g. LogLine.ExitCode, the only install/launch failure
        // signal — AUDIT V1) emitted just before completion must never be dropped.
        val flat = flowOf<LogLine>(LogLine.Stdout("a"), LogLine.ExitCode(0))
            .chunkedByTime(50).toList().flatten()
        assertEquals(listOf(LogLine.Stdout("a"), LogLine.ExitCode(0)), flat)
    }

    @Test
    fun `empty upstream produces no batches`() = runTest {
        assertEquals(emptyList<List<Int>>(), flowOf<Int>().chunkedByTime(50).toList())
    }
}
