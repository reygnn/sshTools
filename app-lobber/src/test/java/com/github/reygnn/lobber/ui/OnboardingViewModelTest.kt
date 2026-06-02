package com.github.reygnn.lobber.ui

import app.cash.turbine.test
import com.github.reygnn.core.testing.MainDispatcherRule
import com.github.reygnn.lobber.R
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ui.UiText
import com.github.reygnn.lobber.ssh.SshBootstrap
import com.github.reygnn.lobber.ssh.SshConfig
import com.github.reygnn.core.ssh.SshKeyPair
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Two-phase onboarding (AUDIT V4): [OnboardingViewModel.start] generates the key
 * and learns the host-key fingerprint *without* sending the password;
 * [OnboardingViewModel.confirmHostKey] only then transmits the password. These
 * tests pin both phases and that the password is withheld until confirmation.
 */
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
        coEvery { bootstrap.discoverHostKey(any(), any()) } returns "SHA256:testfp"
        coEvery { bootstrap.pushPublicKey(any(), any(), any(), any(), any(), any()) } just Runs
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

    /** Runs both onboarding phases (assumes the user confirms the host key). */
    private fun onboard() {
        fillForm()
        vm.start()
        vm.confirmHostKey()
    }

    @Test
    fun `happy path completes with Done step and clears password`() = runTest(mainDispatcherRule.dispatcher) {
        onboard()
        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(OnboardingStep.Done, final.step)
            assertEquals("", final.password)
            assertNull(final.pendingFingerprint)
            assertNull(final.error)
        }
    }

    @Test
    fun `happy path emits exactly one done event`() = runTest(mainDispatcherRule.dispatcher) {
        fillForm()
        vm.doneEvents.test {
            vm.start()
            vm.confirmHostKey()
            awaitItem()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Phase 1 → confirmation gate (AUDIT V4) ────────────────────

    @Test
    fun `start learns the fingerprint and pauses without sending the password`() = runTest(mainDispatcherRule.dispatcher) {
        fillForm()
        vm.start()

        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(OnboardingStep.AwaitingHostKeyConfirm, s.step)
            assertEquals("SHA256:testfp", s.pendingFingerprint)
        }
        coVerify(exactly = 1) { bootstrap.discoverHostKey("buildserver", 2222) }
        coVerify(exactly = 0) { bootstrap.pushPublicKey(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirmHostKey sends the password and pins the confirmed fingerprint`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.discoverHostKey(any(), any()) } returns "SHA256:pinned"
        val pushedFp = slot<String>()
        coEvery { bootstrap.pushPublicKey(any(), any(), any(), any(), any(), capture(pushedFp)) } just Runs

        onboard()

        // The password is only pushed in phase 2, against the confirmed fingerprint.
        coVerify(exactly = 1) { bootstrap.pushPublicKey("buildserver", 2222, "ci", "secret", any(), any()) }
        assertEquals("SHA256:pinned", pushedFp.captured)
    }

    @Test
    fun `cancelHostKey aborts without sending the password and clears it`() = runTest(mainDispatcherRule.dispatcher) {
        fillForm()
        vm.start()
        vm.cancelHostKey()

        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(OnboardingStep.Idle, s.step)
            assertEquals("", s.password)
            assertNull(s.pendingFingerprint)
        }
        coVerify(exactly = 0) { bootstrap.pushPublicKey(any(), any(), any(), any(), any(), any()) }
    }

    // ── Failure paths ─────────────────────────────────────────────

    @Test
    fun `discoverHostKey failure surfaces error and resets to Idle`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.discoverHostKey(any(), any()) } throws IOException("connect refused")
        fillForm()
        vm.start()
        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(OnboardingStep.Idle, final.step)
            assertEquals("", final.password)
            assertEquals(UiText.Literal("IOException: connect refused"), final.error)
        }
        coVerify(exactly = 0) { bootstrap.pushPublicKey(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `failure path emits no done event`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.verifyPubkeyAuth(any()) } throws IOException("nope")
        fillForm()
        vm.doneEvents.test {
            vm.start()
            vm.confirmHostKey()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pushPublicKey failure surfaces error and resets to Idle`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.pushPublicKey(any(), any(), any(), any(), any(), any()) } throws IOException("auth failed")
        onboard()
        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(OnboardingStep.Idle, final.step)
            assertEquals(UiText.Literal("IOException: auth failed"), final.error)
        }
    }

    @Test
    fun `verifyPubkeyAuth failure surfaces error`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.verifyPubkeyAuth(any()) } throws IOException("verification failed")
        onboard()
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
        onboard()
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

    // ── Guards & validation ───────────────────────────────────────

    @Test
    fun `start without filled form yields validation error and does not call bootstrap`() = runTest(mainDispatcherRule.dispatcher) {
        vm.start()
        vm.state.test {
            val final = expectMostRecentItem()
            assertEquals(UiText.Resource(R.string.error_fill_all_fields), final.error)
            assertEquals(OnboardingStep.Idle, final.step)
        }
        coVerify(exactly = 0) { bootstrap.discoverHostKey(any(), any()) }
    }

    @Test
    fun `a second start while one is in flight is ignored`() = runTest(mainDispatcherRule.dispatcher) {
        // Gate discoverHostKey so the first run stays at DiscoveringHost while a
        // second start() is issued — the guard must drop it. See AUDIT V10.
        val gate = CompletableDeferred<String>()
        coEvery { bootstrap.discoverHostKey(any(), any()) } coAnswers { gate.await() }
        fillForm()

        vm.start()   // suspends inside discoverHostKey
        vm.start()   // must be ignored by the in-flight guard
        gate.complete("SHA256:testfp")

        vm.state.test { assertEquals(OnboardingStep.AwaitingHostKeyConfirm, expectMostRecentItem().step) }
        coVerify(exactly = 1) { bootstrap.discoverHostKey(any(), any()) }
    }

    // ── Persistence wiring ────────────────────────────────────────

    @Test
    fun `pubkey is pushed and persisted on happy path`() = runTest(mainDispatcherRule.dispatcher) {
        val pushedLine = slot<String>()
        coEvery {
            bootstrap.pushPublicKey(any(), any(), any(), any(), capture(pushedLine), any())
        } just Runs

        onboard()
        vm.state.test { expectMostRecentItem() }

        assertEquals("ssh-ed25519 AAAA test@host", pushedLine.captured)
        coVerify { settings.savePubKey("ssh-ed25519 AAAA test@host") }
    }

    @Test
    fun `host fingerprint from discovery is pinned onto the verified config and saved profile`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { bootstrap.discoverHostKey(any(), any()) } returns "SHA256:pinned"
        val cfg = slot<SshConfig>()
        coEvery { bootstrap.verifyPubkeyAuth(capture(cfg)) } just Runs

        onboard()
        vm.state.test { expectMostRecentItem() }

        assertEquals("SHA256:pinned", cfg.captured.knownHostFingerprint)
        coVerify {
            settings.saveServers(match { it.size == 1 && it[0].knownHostFingerprint == "SHA256:pinned" })
        }
    }

    @Test
    fun `parsed port is forwarded to discovery, bootstrap and settings`() = runTest(mainDispatcherRule.dispatcher) {
        val discPort = slot<Int>()
        coEvery { bootstrap.discoverHostKey(any(), capture(discPort)) } returns "SHA256:testfp"
        val cfg = slot<SshConfig>()
        coEvery { bootstrap.verifyPubkeyAuth(capture(cfg)) } just Runs

        onboard()
        vm.state.test { expectMostRecentItem() }

        assertEquals(2222, discPort.captured)
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
