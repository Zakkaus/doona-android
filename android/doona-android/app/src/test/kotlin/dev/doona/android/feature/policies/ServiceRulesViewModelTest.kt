package dev.doona.android.feature.policies

import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 新增服務規則 on honk serving the RV sample ([PolicyHonkFixtures.rvSample]). */
class ServiceRulesViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the new rules go to the main group, and a service with a GeoSite rule already has it`() {
        val vm = ServiceRulesViewModel(PolicyHonkFixtures.rvSample(), io = Dispatchers.Unconfined)
        val ui = vm.uiState.value
        assertEquals(RvLabel.Text("Proxy"), ui.main?.name)
        assertEquals(setOf("google"), ui.rules.keys)
        assertEquals("google", (ui.rules.getValue("google").match as RvRuleMatch.GeoSite).category)
    }

    @Test
    fun `ticks are the page's own, and a failed read says why`() {
        val vm = ServiceRulesViewModel(PolicyHonkFixtures.rvSample().apply { on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable")) }, io = Dispatchers.Unconfined)
        vm.toggle("steam")
        vm.toggle("youtube")
        vm.toggle("steam")
        assertEquals(setOf("youtube"), vm.uiState.value.selected)
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
    }
}
