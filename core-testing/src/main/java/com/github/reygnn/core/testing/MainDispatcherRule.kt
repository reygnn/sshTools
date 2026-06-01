package com.github.reygnn.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit4 rule that installs a [StandardTestDispatcher] as [Dispatchers.Main]
 * for the duration of each test and resets it afterwards.
 *
 * Convention: use only this dispatcher — never create a separate TestScope or
 * StandardTestDispatcher inside tests. See TESTING_CONVENTIONS.kt.
 */
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
