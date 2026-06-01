package com.github.reygnn.core.testing

/**
 * TESTING CONVENTIONS — read before writing any test.
 *
 * ## Dispatcher rule
 * Every test class that involves coroutines MUST declare exactly one
 * [MainDispatcherRule]:
 *
 *     @get:Rule val rule = MainDispatcherRule()
 *
 * ## Eager dispatcher
 * The rule's default is an [kotlinx.coroutines.test.UnconfinedTestDispatcher],
 * so coroutines launched on `Dispatchers.Main` start eagerly — no explicit
 * `advanceUntilIdle()`/`runCurrent()` is needed before asserting. If a test
 * genuinely needs virtual-time control, drive it via
 * `rule.dispatcher.scheduler`. Never call `delay()` with real time.
 *
 * ## runTest
 * Wrap coroutine test bodies in `runTest(rule.dispatcher)` — this shares the
 * single dispatcher and keeps virtual time consistent.
 *
 * ## NEVER
 * - Create a second TestScope or TestDispatcher in a test.
 * - Call `kotlinx.coroutines.test.runTest {}` without passing `rule.dispatcher`.
 * - Use `Dispatchers.IO` or `Dispatchers.Default` directly in tests.
 */
@Suppress("unused")
private object TestingConventions
