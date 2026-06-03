package com.github.reygnn.culler.ssh

import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.ssh.SshConnectionParams

data class SshConfig(
    override val host: String,
    override val port: Int = 22,
    override val username: String,
    /** The directory whose entries Culler lists and deletes within. */
    val workingDir: String,
    override val privateKeyPem: String,
    override val knownHostFingerprint: String? = null,
) : SshConnectionParams

fun ServerProfile.toSshConfig(privateKeyPem: String) = SshConfig(
    host = host, port = port, username = username,
    workingDir = workingDir, privateKeyPem = privateKeyPem,
    knownHostFingerprint = knownHostFingerprint,
)

/** One direct child of the managed directory. [isDirectory] decides `rm -rf` vs `rm`. */
data class DirEntry(val name: String, val isDirectory: Boolean)

interface SshClient {
    /** Lists the direct children (files and directories, incl. dotfiles) of the managed directory. */
    suspend fun listEntries(): List<DirEntry>

    /**
     * Deletes one direct child by [name]. Directories are removed recursively
     * (`rm -rf`), files with plain `rm`. Throws on any non-zero remote exit
     * (e.g. permission denied) so the caller can surface the stderr.
     */
    suspend fun deleteEntry(name: String, isDirectory: Boolean)
}
