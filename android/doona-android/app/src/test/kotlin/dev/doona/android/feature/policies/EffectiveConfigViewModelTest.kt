package dev.doona.android.feature.policies

import dev.doona.android.R
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.rvTemplate
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `生效設定 maps honk's groups, rules, template and DNS servers, and follows 依 App`() = runTest {
        val vm = collected()
        engine.perAppLoaded.value = false
        assertNull(vm.uiState.value.perApp)
        assertFalse(requireNotNull(vm.uiState.value.text).contains("# per-app policy:"))
        engine.perAppLoaded.value = true
        val config = vm.uiState.value.config!!

        assertEquals("g-Proxy", config.main?.id)
        assertEquals(7, config.groups.size)
        assertEquals(14, config.rules.size)
        assertEquals(RvRuleSource.Template(rvTemplate("cn-direct")), config.source)
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:53"), config.dns)
        assertEquals(RvLabel.Res(R.string.rv_main_node), config.dnsVia)

        engine.setPerApp(PerApp(policy = AppPolicy.ExceptListed, packages = setOf("a", "b")))
        assertEquals(RvAppPolicy.Except, vm.uiState.value.perApp?.policy)
        assertEquals(setOf("a", "b"), vm.uiState.value.perApp?.packages)
    }

    @Test
    fun `copy and export use a redacted summary rather than the source archive`() = runTest {
        val node = "socks5://user:test-password@private.example:1080"
        honk.on("GET", "/api/v1/x-honk/config/export", 200, "routing { fallback: direct }\nnode { '$node' }")

        val text = checkNotNull(collected().uiState.value.text)

        assertTrue(text.contains("# routing rules: 14"))
        assertFalse(text.contains("test-password"))
        assertFalse(text.contains("private.example"))
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
