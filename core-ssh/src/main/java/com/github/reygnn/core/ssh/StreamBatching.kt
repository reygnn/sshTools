package com.github.reygnn.core.ssh

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Default coalescing window for a streamed UI log (~3 frames at 60 Hz). */
const val STREAM_BATCH_WINDOW_MS: Long = 50

/**
 * Coalesces a chatty upstream into lists emitted at most once per [windowMs], so
 * a UI collector updates its state (and triggers recomposition / auto-scroll)
 * per batch instead of per item. A fast remote command can otherwise emit
 * hundreds of lines per second, each forcing a separate `StateFlow` update,
 * `List` copy and recomposition — even with a virtualized list the *rate* of
 * recompositions saturates the main thread.
 *
 * The flush timer is **one-shot**: it starts only when an item lands in an empty
 * buffer and fires once [windowMs] later. While the stream is open but silent
 * nothing is scheduled, so the operator adds no idle polling (and a never-
 * completing upstream doesn't keep a test scheduler perpetually busy).
 *
 * Order is preserved and the final partial batch is always flushed on completion,
 * so a trailing [LogLine.ExitCode] — the only install/launch failure signal
 * (AUDIT V1) — is never lost.
 */
fun <T> Flow<T>.chunkedByTime(windowMs: Long = STREAM_BATCH_WINDOW_MS): Flow<List<T>> = channelFlow {
    require(windowMs > 0) { "windowMs must be > 0, was $windowMs" }
    val buffer = mutableListOf<T>()
    val mutex = Mutex()
    var flushJob: Job? = null
    try {
        collect { item ->
            val bufferWasEmpty = mutex.withLock {
                buffer.add(item)
                buffer.size == 1
            }
            // Start a single flush timer for this batch; subsequent items in the
            // same window just join the buffer.
            if (bufferWasEmpty) {
                flushJob = launch {
                    delay(windowMs)
                    val batch = mutex.withLock { buffer.toList().also { buffer.clear() } }
                    if (batch.isNotEmpty()) send(batch)
                }
            }
        }
    } finally {
        flushJob?.cancel()
        // Flush whatever is buffered on normal/error completion (isActive), but not
        // when the consumer cancelled us — there's no one left to receive it.
        if (isActive) {
            val tail = mutex.withLock { buffer.toList() }
            if (tail.isNotEmpty()) send(tail)
        }
    }
}
