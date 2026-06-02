package com.github.reygnn.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the shared streaming-log cap. */
class LogLineTest {

    @Test
    fun `plusCapped appends while below the cap`() {
        val log = listOf<LogLine>(LogLine.Stdout("a"))
        assertEquals(
            listOf(LogLine.Stdout("a"), LogLine.Stdout("b")),
            log.plusCapped(LogLine.Stdout("b"), max = 5),
        )
    }

    @Test
    fun `plusCapped drops the oldest lines once over the cap`() {
        val log = (1..5).map { LogLine.Stdout("line$it") }
        val result = log.plusCapped(LogLine.Stdout("line6"), max = 3)
        assertEquals(
            listOf(LogLine.Stdout("line4"), LogLine.Stdout("line5"), LogLine.Stdout("line6")),
            result,
        )
    }

    @Test
    fun `plusCapped keeps only the newest line when cap is one`() {
        val log = listOf<LogLine>(LogLine.Stdout("old"))
        assertEquals(listOf(LogLine.ExitCode(0)), log.plusCapped(LogLine.ExitCode(0), max = 1))
    }
}
