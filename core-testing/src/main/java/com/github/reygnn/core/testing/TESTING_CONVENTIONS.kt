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
 * ## Advance time
 * Use `rule.dispatcher.scheduler.advanceUntilIdle()` (or `runCurrent()`) to
 * drive coroutines. Never call `delay()` with real time.
 *
 * ## runTest
 * Wrap coroutine test bodies in `runTest(rule.dispatcher)` — this shares the
 * single dispatcher and keeps virtual time consistent.
 *
 * ## NEVER
 * - Create a second TestScope or StandardTestDispatcher in a test.
 * - Call `kotlinx.coroutines.test.runTest {}` without passing `rule.dispatcher`.
 * - Use `Dispatchers.IO` or `Dispatchers.Default` directly in tests.
 */
@Suppress("unused")
private object TestingConventions
