package com.github.reygnn.core.onboarding

import app.cash.turbine.test
import com.github.reygnn.core.data.ServerProfile
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ssh.SshKeyPair
import com.github.reygnn.core.ssh.SshOnboarding
import com.github.reygnn.core.testing.MainDispatcherRule
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Two-phase onboarding logic (AUDIT V4), shared by all three apps. The controller
 * takes an injected scope, so tests drive it with the rule's eager dispatcher.
 */
class OnboardingControllerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = mockk<SettingsStore>()
    private val onboarding = mockk<SshOnboarding>()
    private val testKeyPair = SshKeyPair(
        privateKeyPem = "PEM-BLOCK",
        publicKeyOpenSsh = "ssh-ed25519 AAAA test@host",
    )

    private fun newController(requireWorkingDir: Boolean = true) = OnboardingController(
        settings = settings,
        scope = CoroutineScope(mainDispatcherRule.dispatcher),
        requireWorkingDir = requireWorkingDir,
        onboarding = onboarding,
        keygen = { testKeyPair },
    )

    @Before
    fun setUp() {
        coEvery { settings.saveKey(any()) } just Runs
        coEvery { settings.saveServers(any()) } just Runs
        coEvery { settings.savePubKey(any()) } just Runs
        coEvery { onboarding.discoverHostKey(any(), any()) } returns "SHA256:testfp"
        coEvery { onboarding.pushPublicKey(any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { onboarding.verifyPubkeyAuth(any(), any(), any(), any(), any()) } just Runs
    }

    private fun OnboardingController.fill() {
        onHost("buildserver"); onPort("2222"); onUsername("ci")
        onPassword("secret"); onWorkingDir("/srv/builds")
    }

    @Test
    fun `happy path completes with Done step and clears password`() = runTest(mainDispatcherRule.dispatcher) {
        val c = newController()
        c.fill(); c.start(); c.confirmHostKey()
        c.state.test {
            val final = expectMostRecentItem()
            assertEquals(OnboardingStep.Done, final.step)
            assertEquals("", final.password)
            assertNull(final.pendingFingerprint)
            assertNull(final.error)
        }
    }

    @Test
    fun `start learns the fingerprint and pauses without sending the password`() = runTest(mainDispatcherRule.dispatcher) {
        val c = newController()
        c.fill(); c.start()
        c.state.test {
            val s = expectMostRecentItem()
            assertEquals(OnboardingStep.AwaitingHostKeyConfirm, s.step)
            assertEquals("SHA256:testfp", s.pendingFingerprint)
        }
        coVerify(exactly = 1) { onboarding.discoverHostKey("buildserver", 2222) }
        coVerify(exactly = 0) { onboarding.pushPublicKey(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `confirmHostKey sends the password and pins the confirmed fingerprint`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { onboarding.discoverHostKey(any(), any()) } returns "SHA256:pinned"
        val pushedFp = slot<String>()
        coEvery { onboarding.pushPublicKey(any(), any(), any(), any(), any(), capture(pushedFp)) } just Runs
        val c = newController()

        c.fill(); c.start(); c.confirmHostKey()

        coVerify(exactly = 1) { onboarding.pushPublicKey("buildserver", 2222, "ci", "secret", any(), any()) }
        assertEquals("SHA256:pinned", pushedFp.captured)
    }

    @Test
    fun `cancelHostKey aborts without sending the password and clears it`() = runTest(mainDispatcherRule.dispatcher) {
        val c = newController()
        c.fill(); c.start(); c.cancelHostKey()
        c.state.test {
            val s = expectMostRecentItem()
            assertEquals(OnboardingStep.Idle, s.step)
            assertEquals("", s.password)
            assertNull(s.pendingFingerprint)
        }
        coVerify(exactly = 0) { onboarding.pushPublicKey(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `emits exactly one done event on the happy path`() = runTest(mainDispatcherRule.dispatcher) {
        val c = newController()
        c.fill()
        c.doneEvents.test {
            c.start(); c.confirmHostKey()
            awaitItem()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty required field yields EmptyFields and calls no SSH`() = runTest(mainDispatcherRule.dispatcher) {
        val c = newController()
        c.onHost("h"); c.onUsername("u")  // password + workingDir blank
        c.start()
        c.state.test {
            val s = expectMostRecentItem()
            assertEquals(OnboardingError.EmptyFields, s.error)
            assertEquals(OnboardingStep.Idle, s.step)
        }
        coVerify(exactly = 0) { onboarding.discoverHostKey(any(), any()) }
    }

    @Test
    fun `requireWorkingDir false allows a blank working dir`() = runTest(mainDispatcherRule.dispatcher) {
        val c = newController(requireWorkingDir = false)
        c.onHost("h"); c.onUsername("u"); c.onPassword("p")  // no workingDir
        c.start()
        c.state.test {
            assertEquals(OnboardingStep.AwaitingHostKeyConfirm, expectMostRecentItem().step)
        }
    }

    @Test
    fun `discoverHostKey failure surfaces a Failure error and resets to Idle`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { onboarding.discoverHostKey(any(), any()) } throws IOException("connect refused")
        val c = newController()
        c.fill(); c.start()
        c.state.test {
            val s = expectMostRecentItem()
            assertEquals(OnboardingStep.Idle, s.step)
            assertEquals("", s.password)
            assertEquals(OnboardingError.Failure("IOException: connect refused"), s.error)
        }
        coVerify(exactly = 0) { onboarding.pushPublicKey(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pushPublicKey failure surfaces a Failure error`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { onboarding.pushPublicKey(any(), any(), any(), any(), any(), any()) } throws IOException("auth failed")
        val c = newController()
        c.fill(); c.start(); c.confirmHostKey()
        c.state.test {
            val s = expectMostRecentItem()
            assertEquals(OnboardingStep.Idle, s.step)
            assertEquals(OnboardingError.Failure("IOException: auth failed"), s.error)
        }
    }

    @Test
    fun `verifyPubkeyAuth failure includes the cause chain`() = runTest(mainDispatcherRule.dispatcher) {
        val root = IllegalStateException("Ed25519 not found")
        val mid = java.security.GeneralSecurityException("KeyFactory failed", root)
        coEvery { onboarding.verifyPubkeyAuth(any(), any(), any(), any(), any()) } throws
            IOException("Read OpenSSH Version 1 Key failed", mid)
        val c = newController()
        c.fill(); c.start(); c.confirmHostKey()
        c.state.test {
            assertEquals(
                OnboardingError.Failure(
                    "IOException: Read OpenSSH Version 1 Key failed\n" +
                        "→ GeneralSecurityException: KeyFactory failed\n" +
                        "→ IllegalStateException: Ed25519 not found"
                ),
                expectMostRecentItem().error,
            )
        }
    }

    @Test
    fun `a second start while one is in flight is ignored`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<String>()
        coEvery { onboarding.discoverHostKey(any(), any()) } coAnswers { gate.await() }
        val c = newController()
        c.fill()

        c.start()   // suspends inside discoverHostKey
        c.start()   // must be ignored by the in-flight guard
        gate.complete("SHA256:testfp")

        c.state.test { assertEquals(OnboardingStep.AwaitingHostKeyConfirm, expectMostRecentItem().step) }
        coVerify(exactly = 1) { onboarding.discoverHostKey(any(), any()) }
    }

    @Test
    fun `persists key, profile with pin and working dir, and forwards the parsed port`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { onboarding.discoverHostKey(any(), any()) } returns "SHA256:pinned"
        val saved = slot<List<ServerProfile>>()
        coEvery { settings.saveServers(capture(saved)) } just Runs
        val c = newController()

        c.fill(); c.start(); c.confirmHostKey()
        c.state.test { expectMostRecentItem() }

        coVerify { settings.saveKey("PEM-BLOCK") }
        coVerify { settings.savePubKey("ssh-ed25519 AAAA test@host") }
        coVerify { onboarding.verifyPubkeyAuth("buildserver", 2222, "ci", "PEM-BLOCK", "SHA256:pinned") }
        val profile = saved.captured.single()
        assertEquals("buildserver", profile.host)
        assertEquals(2222, profile.port)
        assertEquals("ci", profile.username)
        assertEquals("/srv/builds", profile.workingDir)
        assertEquals("SHA256:pinned", profile.knownHostFingerprint)
        assertTrue(profile.name.isNotBlank())
    }
}
