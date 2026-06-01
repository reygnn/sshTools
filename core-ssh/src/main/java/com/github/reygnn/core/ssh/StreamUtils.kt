package com.github.reygnn.core.ssh

import java.io.InputStream

/**
 * Reads at most [maxBytes] bytes as UTF-8, then stops. Bounds memory against
 * a runaway or hostile host that never stops writing.
 */
fun InputStream.readCapped(maxBytes: Int): String {
    val sb = StringBuilder()
    val chunk = CharArray(8192)
    var total = 0
    bufferedReader().use { reader ->
        while (total < maxBytes) {
            val read = reader.read(chunk, 0, minOf(chunk.size, maxBytes - total))
            if (read < 0) break
            sb.append(chunk, 0, read)
            total += read
        }
    }
    return sb.toString()
}
