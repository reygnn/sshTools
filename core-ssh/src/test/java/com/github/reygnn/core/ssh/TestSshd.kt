package com.github.reygnn.core.ssh

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator
import org.apache.sshd.server.command.AbstractCommandSupport
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * In-process Apache MINA SSHD for the SSH integration tests. Runs a *real* SSH
 * server on an ephemeral loopback port — real KEX, real Ed25519 pubkey/password
 * auth, real command exec — so the tests drive the actual sshj client path
 * (`connectWithKey`, `BcOpenSshKeyProvider`, `runCommand`, `streamCommand`,
 * `SshjOnboarding`) end-to-end instead of a mock.
 *
 * MINA is an independent SSH implementation, so this also exercises real
 * interop, not just our code talking to itself.
 *
 * Each instance generates a fresh in-memory host key (so a new instance = a
 * rotated host key, which the TOFU tests rely on). Commands run through
 * `/bin/sh -c` with [home] as `$HOME`, so pipes, `&&` and `~` expansion behave
 * exactly as on a real host.
 *
 * Auth: [authorizedKeys] points at a real `authorized_keys` file (reloaded as it
 * changes), [password] enables `user:pass` login. Pubkey auth is checked against
 * the file rather than against a key object — comparing `PublicKey` instances is
 * unreliable across providers (sshj/BC mint `Ed25519`, MINA's eddsa lib mints
 * `EdDSA`), whereas the OpenSSH authorized_keys line is canonical.
 */
class TestSshd(
    home: Path,
    authorizedKeys: Path? = null,
    password: Pair<String, String>? = null,
) : AutoCloseable {

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0 // ephemeral
        keyPairProvider = SimpleGeneratorHostKeyProvider() // fresh host key per instance
        publickeyAuthenticator = if (authorizedKeys != null) {
            AuthorizedKeysAuthenticator(authorizedKeys)
        } else {
            PublickeyAuthenticator { _, _, _ -> false }
        }
        password?.let { (user, pass) ->
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == user && p == pass }
        }
        commandFactory = CommandFactory { _, command -> ShCommand(command, home) }
        start()
    }

    val port: Int get() = server.port

    override fun close() {
        server.stop(true)
    }

    /**
     * Runs each exec request through `/bin/sh -c` (so pipes, `&&`, redirects and
     * `~` work) with `$HOME` pointed at the test's temp dir. stdout and stderr are
     * drained on separate threads so neither buffer can block the other.
     */
    private class ShCommand(
        private val command: String,
        private val home: Path,
    ) : AbstractCommandSupport(command, null) {

        override fun run() {
            try {
                val process = ProcessBuilder("/bin/sh", "-c", command)
                    .apply { environment()["HOME"] = home.toString() }
                    .start()
                val errPump = thread {
                    process.errorStream.copyTo(errorStream)
                    errorStream.flush()
                }
                process.inputStream.copyTo(outputStream)
                outputStream.flush()
                errPump.join()
                onExit(process.waitFor())
            } catch (e: Exception) {
                onExit(1, e.message ?: "command failed")
            }
        }
    }
}
