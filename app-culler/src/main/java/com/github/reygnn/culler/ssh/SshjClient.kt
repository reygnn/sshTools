package com.github.reygnn.culler.ssh

import com.github.reygnn.core.ssh.DEFAULT_READ_TIMEOUT_MS
import com.github.reygnn.core.ssh.RemoteCommandException
import com.github.reygnn.core.ssh.connectWithKey
import com.github.reygnn.core.ssh.pathQuote
import com.github.reygnn.core.ssh.runCommand
import com.github.reygnn.core.ssh.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SshjClient(
    private val config: SshConfig,
    private val onLearnHostKey: (String) -> Unit = {},
) : SshClient {

    private fun connect(readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS) =
        connectWithKey(config, readTimeoutMs = readTimeoutMs, onLearnHostKey = onLearnHostKey)

    override suspend fun listEntries(): List<DirEntry> = withContext(Dispatchers.IO) {
        connect().use { ssh ->
            // LC_ALL=C keeps find output locale-independent (stable parsing). `find -L`
            // follows symlinks so a link's target type decides the kind. `-mindepth 1`
            // drops the directory itself; `%y\t%f` is the type letter + bare name.
            val (exit, out, err) = ssh.runCommand(
                "cd ${pathQuote(config.workingDir)} && " +
                    "LC_ALL=C find -L . -maxdepth 1 -mindepth 1 -printf '%y\\t%f\\n'"
            )
            if (exit != 0) throw RemoteCommandException(exit, err.trim())
            parseFindEntries(out)
        }
    }

    override suspend fun deleteEntry(name: String, isDirectory: Boolean): Unit = withContext(Dispatchers.IO) {
        require(isSafeEntryName(name)) { "Unsafe entry name: $name" }
        connect().use { ssh ->
            // `cd` into the managed dir so the name stays a relative basename; `--`
            // ends option parsing (guards a leading '-'); shellQuote handles the rest.
            val rm = if (isDirectory) "rm -rf --" else "rm --"
            val (exit, _, err) = ssh.runCommand(
                "cd ${pathQuote(config.workingDir)} && $rm ${shellQuote(name)}"
            )
            if (exit != 0) throw RemoteCommandException(exit, err.trim())
        }
    }
}

/**
 * Parses the `find … -printf '%y\t%f\n'` output into [DirEntry]s. Each line is
 * `<type-letter>\t<name>`; only the type 'd' is treated as a directory. The
 * directory itself, `.` and `..` are filtered out (mindepth already excludes the
 * first, the others are belt-and-suspenders). Line/tab-based, so names containing
 * a literal newline or tab are not representable — a deliberate, pathological-case
 * limitation matching the rest of the codebase's line parsing.
 */
internal fun parseFindEntries(findOutput: String): List<DirEntry> =
    findOutput.lineSequence().mapNotNull { line ->
        if (line.isEmpty()) return@mapNotNull null
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val name = line.substring(tab + 1)
        if (!isSafeEntryName(name)) return@mapNotNull null
        DirEntry(name = name, isDirectory = line.substring(0, tab) == "d")
    }.toList()

/**
 * A name is safe to interpolate into a `cd <dir> && rm -- <name>` command only if
 * it is a single path component: non-empty, no `/`, and not `.`/`..`. shellQuote
 * neutralizes every other metacharacter, so this is purely a path-traversal /
 * empty-arg guard.
 */
internal fun isSafeEntryName(name: String): Boolean =
    name.isNotEmpty() && !name.contains('/') && name != "." && name != ".."
