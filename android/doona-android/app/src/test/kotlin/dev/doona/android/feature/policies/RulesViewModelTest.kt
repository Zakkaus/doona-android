package dev.doona.android.feature.policies

import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 全部規則 on honk answering with its contract's examples ([HonkFixtures]). */
class RulesViewModelTest {
    private val honk = PolicyHonkFixtures.running()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = RulesViewModel(honk, io = UnconfinedTestDispatcher(testScheduler))

    @Test
    fun listsEveryRuleInOrderWithTheFallbackLast() = runTest {
        val state = viewModel().uiState.value

        assertFalse(state.loading)
        assertNull(state.failure)
        val (curl, range, rest) = state.rules
        assertEquals(null, (curl.match as RvRuleMatch.Value).kind)
        assertEquals("pname(curl)", (curl.match as RvRuleMatch.Value).value)
        assertEquals(RvRuleEnd.Direct, curl.end)
        assertEquals("198.51.100.0/24", (range.match as RvRuleMatch.Value).value)
        assertEquals(RvLabel.Text("proxy"), range.group?.name)
        assertEquals(RvRuleMatch.Rest, rest.match)
        assertEquals(RvRuleEnd.Reject, rest.end)
    }

    @Test
    fun aFailedReadKeepsTheRulesAndRetryReadsAgain() = runTest {
        val vm = viewModel()
        honk.on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable"))

        vm.retry()

        assertEquals(RulesFailure.Read(ErrorCode.Busy), vm.uiState.value.failure)
        assertEquals(3, vm.uiState.value.rules.size)

        honk.on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/running"))
        vm.retry()

        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun aFirstReadThatFailsShowsNoRules() = runTest {
        honk.on("GET", "/api/v1/groups", 503, HonkFixtures.body("errors/snapshot-unavailable"))

        val state = viewModel().uiState.value

        assertFalse(state.loading)
        assertEquals(RulesFailure.Read(ErrorCode.Busy), state.failure)
        assertEquals(emptyList<Any>(), state.rules)
    }

    @Test
    fun filesHonkDidNotLoadListNoRules() = runTest {
        honk.on("GET", "/api/v1/runtime", 200, HonkFixtures.body("runtime/runtime-reload-failed"))

        val state = viewModel().uiState.value

        assertEquals(RulesFailure.Parse, state.failure)
        assertEquals(emptyList<Any>(), state.rules)
    }
}
