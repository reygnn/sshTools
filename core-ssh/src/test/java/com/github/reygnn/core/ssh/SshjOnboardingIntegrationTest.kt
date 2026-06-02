package com.github.reygnn.core.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * The two-phase onboarding handshake (AUDIT V4) against a real in-process server
 * ([TestSshd]): password login, public key appended to a real `authorized_keys`,
 * then pubkey auth verified against that same file. The mock-based
 * `OnboardingControllerTest` pins the state machine; this pins the wire protocol.
 */
class SshjOnboardingIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val onboarding = SshjOnboarding()

    @Before
    fun setUp() {
        SshSecurity.installBouncyCastle()
    }

    @Test
    fun `two-phase onboarding pushes the key then authenticates with it`() = runBlocking {
        val home = tmp.root.toPath()
        val authorizedKeys = home.resolve(".ssh/authorized_keys")
        val pair = SshKeygen.generateEd25519()

        TestSshd(home = home, authorizedKeys = authorizedKeys, password = "builder" to "s3cret").use { server ->
            // Phase 1: learn the host key without sending the password.
            val fingerprint = onboarding.discoverHostKey("127.0.0.1", server.port)
            assertTrue(fingerprint.startsWith("SHA256:"))

            // Phase 2: pin the fingerprint, send the password, append the pubkey.
            onboarding.pushPublicKey(
                host = "127.0.0.1", port = server.port, username = "builder",
                password = "s3cret", publicKeyLine = pair.publicKeyOpenSsh,
                expectedFingerprint = fingerprint,
            )
            assertTrue(
                "authorized_keys must contain the pushed public key",
                String(Files.readAllBytes(authorizedKeys)).contains(pair.publicKeyOpenSsh),
            )

            // The freshly authorized key must now authenticate against the pinned host.
            onboarding.verifyPubkeyAuth(
                host = "127.0.0.1", port = server.port, username = "builder",
                privateKeyPem = pair.privateKeyPem, knownHostFingerprint = fingerprint,
            )
        }
    }

    @Test
    fun `a wrong password fails the push`(): Unit = runBlocking {
        val home = tmp.root.toPath()
        TestSshd(
            home = home,
            authorizedKeys = home.resolve(".ssh/authorized_keys"),
            password = "builder" to "right",
        ).use { server ->
            val fingerprint = onboarding.discoverHostKey("127.0.0.1", server.port)
            val pair = SshKeygen.generateEd25519()
            assertThrows(Exception::class.java) {
                runBlocking {
                    onboarding.pushPublicKey(
                        host = "127.0.0.1", port = server.port, username = "builder",
                        password = "wrong", publicKeyLine = pair.publicKeyOpenSsh,
                        expectedFingerprint = fingerprint,
                    )
                }
            }
        }
    }
}
