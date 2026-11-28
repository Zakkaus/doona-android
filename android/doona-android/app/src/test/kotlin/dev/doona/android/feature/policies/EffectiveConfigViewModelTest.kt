package dev.doona.android.feature.policies

import dev.doona.android.R
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.rvTemplate
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 生效設定 on honk serving the RV sample ([PolicyHonkFixtures.rvSample]); the mode and 依 App are the engine's. */
class EffectiveConfigViewModelTest {
    private val honk = PolicyHonkFixtures.rvSample()
    private val engine = rvSampleEngine()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collected() = EffectiveConfigViewModel(engine, honk, io = UnconfinedTestDispatcher(testScheduler)).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `生效設定 maps honk's groups, rules, template, DNS servers and export, and follows 依 App`() = runTest {
        val vm = collected()
        val config = vm.uiState.value.config!!

        assertEquals("g-Proxy", config.main?.id)
        assertEquals(7, config.groups.size)
        assertEquals(14, config.rules.size)
        assertEquals(RvRuleSource.Template(rvTemplate("cn-direct")), config.source)
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:53"), config.dns)
        assertEquals(RvLabel.Res(R.string.rv_main_node), config.dnsVia)
        assertTrue(vm.uiState.value.text!!.contains("routing {"))

        engine.setPerApp(PerApp(packages = setOf("a", "b")))
        assertEquals(2, vm.uiState.value.config?.proxiedApps)
    }

    @Test
    fun `a server that names no group goes direct`() = runTest {
        val sample = HonkFixtures.body("config/rv-sample")
        honk.on("GET", "/api/v1/config", 200, sample.replace("'udp://1.1.1.1:53' -> Proxy", "'udp://1.1.1.1:53'"))

        assertEquals(RvLabel.Res(R.string.mode_direct), collected().uiState.value.config?.dnsVia)
    }

    @Test
    fun `a failed read shows why over the config last read, and 重試 reads again`() = runTest {
        honk.on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        val vm = collected()
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
        assertNull(vm.uiState.value.config)

        honk.on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/rv-sample"))
        vm.retry()
        assertNull(vm.uiState.value.failure)
        assertNotNull(vm.uiState.value.config)

        honk.on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        vm.retry()
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
        assertEquals("the config last read stays", "g-Proxy", vm.uiState.value.config?.main?.id)
    }
}
