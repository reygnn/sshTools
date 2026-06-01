package com.github.reygnn.core.ssh

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads at most [maxBytes] bytes, then stops, decoding the result as UTF-8.
 * Bounds memory against a runaway or hostile host that never stops writing.
 *
 * The cap is counted in bytes (not characters): a multi-byte UTF-8 sequence
 * straddling the [maxBytes] boundary may decode to the Unicode replacement
 * character, which is acceptable for a defensive output cap.
 */
fun InputStream.readCapped(maxBytes: Int): String {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    use { input ->
        while (total < maxBytes) {
            val read = input.read(chunk, 0, minOf(chunk.size, maxBytes - total))
            if (read < 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
    }
    return String(buffer.toByteArray(), Charsets.UTF_8)
}
