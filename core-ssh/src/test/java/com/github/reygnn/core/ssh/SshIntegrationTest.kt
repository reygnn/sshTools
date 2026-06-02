package com.github.reygnn.core.ssh

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end SSH integration tests against an in-process MINA server ([TestSshd]).
 * Unlike the mock-based ViewModel tests, these drive the real client path: a key
 * minted by [SshKeygen], loaded by [BcOpenSshKeyProvider], authenticated over a
 * real handshake by [connectWithKey], with output drained by [runCommand] /
 * [streamCommand].
 *
 * Caveat: on the desktop JVM the JCE serves Ed25519 broadly, so the specific
 * Conscrypt PKCS#8-preamble bug that [BcOpenSshKeyProvider] works around does not
 * reproduce here — that needs an instrumented (on-device) run. These tests pin
 * the protocol wiring and the concurrency contracts (drain-before-join,
 * cancellation teardown, TOFU pinning), which are otherwise wholly unexercised.
 */
class SshIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val device = SshKeygen.generateEd25519()
    private lateinit var authorizedKeys: Path
    private lateinit var server: TestSshd

    @Before
    fun setUp() {
        SshSecurity.installBouncyCastle() // the client needs full BC for Ed25519
        authorizedKeys = tmp.newFile("authorized_keys").toPath()
        Files.write(authorizedKeys, device.publicKeyOpenSsh.toByteArray())
        server = TestSshd(home = tmp.root.toPath(), authorizedKeys = authorizedKeys)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun connect(
        pin: String? = null,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        onLearn: (String) -> Unit = {},
    ): SSHClient = connectWithKey(
        host = "127.0.0.1",
        port = server.port,
        username = "builder",
        privateKeyPem = device.privateKeyPem,
        knownHostFingerprint = pin,
        readTimeoutMs = readTimeoutMs,
        onLearnHostKey = onLearn,
    )

    @Test
    fun `generated Ed25519 key authenticates and runs a command`() = runBlocking {
        connect().use { ssh ->
            val result = ssh.runCommand("printf 'hello'")
            assertEquals(0, result.exitStatus)
            assertEquals("hello", result.stdout)
        }
    }

    @Test
    fun `non-zero exit status and stderr are reported`() = runBlocking {
        connect().use { ssh ->
            val result = ssh.runCommand("printf 'nope' >&2; exit 7")
            assertEquals(7, result.exitStatus)
            assertEquals("nope", result.stderr)
        }
    }

    @Test(timeout = 20_000)
    fun `runCommand drains stdout and stderr concurrently without deadlocking`() = runBlocking {
        connect().use { ssh ->
            // 2 MiB to each stream: enough to exceed the SSH channel window, so a
            // naive "read stdout fully, then stderr" would block the host mid-write
            // and deadlock. runCommand drains both concurrently before join (Hard
            // Rule 3), so this completes and captures everything. The timeout turns
            // a regression into a failure instead of a frozen build.
            val twoMiB = 2 * 1024 * 1024
            val result = ssh.runCommand(
                "head -c $twoMiB /dev/zero | tr '\\0' o ; head -c $twoMiB /dev/zero | tr '\\0' e >&2",
                maxOutputBytes = 8 * 1024 * 1024,
            )
            assertEquals(0, result.exitStatus)
            assertEquals(twoMiB, result.stdout.length)
            assertEquals(twoMiB, result.stderr.length)
        }
    }

    @Test
    fun `first connect learns the fingerprint and a matching pin reconnects`() = runBlocking {
        val learned = AtomicReference<String?>()
        connect(onLearn = { learned.set(it) }).use { it.runCommand("true") }

        val fingerprint = learned.get()
        assertNotNull("first connect must learn a fingerprint", fingerprint)
        assertTrue(fingerprint!!.startsWith("SHA256:"))

        // Reconnect with the pin: must succeed and not re-learn anything.
        val relearned = AtomicReference<String?>()
        connect(pin = fingerprint, onLearn = { relearned.set(it) }).use {
            assertEquals(0, it.runCommand("true").exitStatus)
        }
        assertEquals(null, relearned.get())
    }

    @Test
    fun `a rotated host key aborts a pinned reconnect`(): Unit = runBlocking {
        val learned = AtomicReference<String?>()
        connect(onLearn = { learned.set(it) }).use { it.runCommand("true") }

        // New server instance = new host key; the old pin must no longer match.
        server.close()
        server = TestSshd(home = tmp.root.toPath(), authorizedKeys = authorizedKeys)

        assertThrows(Exception::class.java) {
            runBlocking { connect(pin = learned.get()).use { } }
        }
    }

    @Test(timeout = 20_000)
    fun `streamCommand emits each line then a terminal ExitCode`() = runBlocking {
        val lines = streamCommand({ connect(readTimeoutMs = 0) }, "printf 'a\\nb\\nc\\n'").toList()

        assertEquals(
            listOf("a", "b", "c"),
            lines.filterIsInstance<LogLine.Stdout>().map { it.text },
        )
        assertEquals(LogLine.ExitCode(0), lines.last())
    }

    @Test(timeout = 20_000)
    fun `cancelling a streaming collect tears the connection down promptly`() = runBlocking {
        val job = launch {
            // No read timeout (an install can be silent for minutes); the only
            // recovery from a stalled stream is cancellation (AUDIT V5).
            streamCommand({ connect(readTimeoutMs = 0) }, "sleep 30").collect { }
        }
        delay(750) // let it connect and block on the silent stream
        job.cancelAndJoin() // must return well under the 20s timeout
        assertTrue(job.isCancelled)
    }
}
