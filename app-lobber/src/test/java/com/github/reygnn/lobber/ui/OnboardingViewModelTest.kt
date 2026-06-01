package com.github.reygnn.lobber.ui

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.lobber.R
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.lobber.ssh.SshBootstrap
import com.github.reygnn.lobber.ssh.SshConfig
import com.github.reygnn.lobber.ssh.SshKeyPair
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>()
    private val bootstrap = mockk<SshBootstrap>()
    private val testKeyPair = SshKeyPair(
        privateKeyPem = "PEM-BLOCK",
        publicKeyOpenSsh = "ssh-ed25519 AAAA test@host",
    )

    private lateinit var vm: OnboardingViewModel

    @Before
    fun setUp() {
        coEvery { settings.saveKey(any()) } just Runs
        coEvery { settings.saveServers(any()) } just Runs
        coEvery { settings.savePubKey(any()) } just Runs
        coEvery { bootstrap.pushPublicKey(any(), any(), any(), any(), any()) } returns "SHA256:testfp"
        coEvery { bootstrap.verifyPubkeyAuth(any()) } just Runs
        vm = OnboardingViewModel(settings = settings, bootstrap = bootstrap, keygen = { testKeyPair })
    }

    private fun fillForm() {
        vm.onHost("buildserver")
        vm.onPort("2222")
        vm.onUsername("ci")
        vm.onPassword("secret")
        vm.onWorkingDir("/srv/builds")
    }

    @Test
    fun `happy path completes with Done step and clears password`() = runTest(mainDispatcherRule.dispatcher) {
        fillForm()
        vm.start()
        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(OnboardingStep.Done, final.step)
            assertEquals("", final.password)
            assertNull(final.error)
        }
    }

    @Test
    fun `happy path emits exactly one done event`() = runTest(mainDispatcherRule.dispatcher) {
        fillForm()
        vm.start()
        vm.doneEvents.test {
            awaitItem()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failure path emits no done event`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.verifyPubkeyAuth(any()) } throws IOException("nope")
        fillForm()
        vm.start()
        vm.doneEvents.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pushPublicKey failure surfaces error and resets to Idle`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.pushPublicKey(any(), any(), any(), any(), any()) } throws IOException("auth failed")
        fillForm()
        vm.start()
        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(OnboardingStep.Idle, final.step)
            assertEquals(UiText.Literal("IOException: auth failed"), final.error)
        }
    }

    @Test
    fun `verifyPubkeyAuth failure surfaces error`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.verifyPubkeyAuth(any()) } throws IOException("verification failed")
        fillForm()
        vm.start()
        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(OnboardingStep.Idle, final.step)
            assertEquals(UiText.Literal("IOException: verification failed"), final.error)
        }
    }

    @Test
    fun `error message includes cause chain`() = runTest(mainDispatcherRule.dispatcher) {
        val root = IllegalStateException("Ed25519 not found")
        val mid = java.security.GeneralSecurityException("KeyFactory failed", root)
        coEvery { bootstrap.verifyPubkeyAuth(any()) } throws IOException("Read OpenSSH Version 1 Key failed", mid)
        fillForm()
        vm.start()
        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(
                UiText.Literal(
                    "IOException: Read OpenSSH Version 1 Key failed\n" +
                        "→ GeneralSecurityException: KeyFactory failed\n" +
                        "→ IllegalStateException: Ed25519 not found"
                ),
                final.error,
            )
        }
    }

    @Test
    fun `start without filled form yields validation error and does not call bootstrap`() = runTest(mainDispatcherRule.dispatcher) {
        vm.start()
        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(UiText.Resource(R.string.error_fill_all_fields), final.error)
            assertEquals(OnboardingStep.Idle, final.step)
        }
        coVerify(exactly = 0) { bootstrap.pushPublicKey(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pubkey is pushed and persisted on happy path`() = runTest(mainDispatcherRule.dispatcher) {
        val pushedLine = slot<String>()
        coEvery {
            bootstrap.pushPublicKey(any(), any(), any(), any(), capture(pushedLine))
        } returns "SHA256:testfp"

        fillForm()
        vm.start()
        vm.state.test { expectMostRecentItem() }

        assertEquals("ssh-ed25519 AAAA test@host", pushedLine.captured)
        coVerify { settings.savePubKey("ssh-ed25519 AAAA test@host") }
    }

    @Test
    fun `host fingerprint from push is pinned onto the verified config and saved profile`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.pushPublicKey(any(), any(), any(), any(), any()) } returns "SHA256:pinned"
        val cfg = slot<SshConfig>()
        coEvery { bootstrap.verifyPubkeyAuth(capture(cfg)) } just Runs

        fillForm()
        vm.start()
        vm.state.test { expectMostRecentItem() }

        assertEquals("SHA256:pinned", cfg.captured.knownHostFingerprint)
        coVerify {
            settings.saveServers(match { it.size == 1 && it[0].knownHostFingerprint == "SHA256:pinned" })
        }
    }

    @Test
    fun `parsed port is forwarded to bootstrap and settings`() = runTest(mainDispatcherRule.dispatcher) {
        val cfg = slot<SshConfig>()
        coEvery { bootstrap.verifyPubkeyAuth(capture(cfg)) } just Runs

        fillForm()
        vm.start()
        vm.state.test { expectMostRecentItem() }

        assertEquals(2222, cfg.captured.port)
        coVerify { settings.saveKey("PEM-BLOCK") }
        coVerify {
            settings.saveServers(
                match {
                    it.size == 1 &&
                        it[0].host == "buildserver" &&
                        it[0].port == 2222 &&
                        it[0].username == "ci" &&
                        it[0].workingDir == "/srv/builds"
                }
            )
        }
    }
}
