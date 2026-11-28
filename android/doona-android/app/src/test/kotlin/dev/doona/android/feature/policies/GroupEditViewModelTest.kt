package dev.doona.android.feature.policies

import dev.doona.android.engine.ErrorCode
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.HonkGroupPolicy
import dev.doona.android.honk.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The group editor on honk's contract examples: group-proxy, its `/config`, and the editable config. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupEditViewModelTest {
    private val io = UnconfinedTestDispatcher()
    private val honk = PolicyHonkFixtures.running().apply {
        on("GET", "/api/v1/groups/group-proxy/config", 200, HonkFixtures.body("group-config/current"))
        on("PATCH", "/api/v1/groups/group-proxy/config", 202, HonkFixtures.body("group-config/patch-queued"))
        on("GET", "/api/v1/operations/op-01HZX4K8WA", 200, HonkFixtures.body("operations/reload-complete"))
    }

    @Before
    fun setUp() = Dispatchers.setMain(io)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the editor reads the group, its members and its file, and 套用 patches what changed`() = runTest {
        val vm = GroupEditViewModel(honk, "group-proxy", io = io)
        val ui = vm.uiState.value
        assertEquals("proxy", ui.name)
        assertEquals(listOf("node-hk-01" to false, "group-jp" to true), ui.members.map { it.id to it.group })
        assertEquals("config.dae", ui.source)
        val draft = checkNotNull(ui.draft)
        vm.change(draft.copy(policy = HonkGroupPolicy("selector", "selector"), config = draft.config.copy(defaultMemberId = "node-hk-01")))
        vm.apply()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        val patch = checkNotNull(honk.calls.single { it.method == "PATCH" }.body)
        assertTrue(patch, patch.contains(""""path":"/policy","value":{"kind":"selector","native":"selector"}"""))
        assertTrue(patch, patch.contains(""""path":"/config/default_member_id","value":"node-hk-01""""))
    }

    @Test
    fun `a change made elsewhere is busy and the page stays`() = runTest {
        honk.on("PATCH", "/api/v1/groups/group-proxy/config", 409, HonkFixtures.body("errors/snapshot-unavailable"))
        val vm = GroupEditViewModel(honk, "group-proxy", io = io)
        val draft = checkNotNull(vm.uiState.value.draft)
        vm.change(draft.copy(config = draft.config.copy(finalOutbound = "block")))
        vm.apply()
        advanceUntilIdle()
        assertEquals(ErrorCode.Busy, vm.uiState.value.writeFailure)
        assertFalse(vm.uiState.value.done)
    }
}
