package dev.doona.android.feature.policies

import dev.doona.android.R
import dev.doona.android.engine.EffectiveConfig
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.RuleTemplate
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.rvTemplate
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
import org.junit.Before
import org.junit.Test

class TemplateAndEffectiveConfigTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val source = rvSampleEngine().catalog.value!!.ruleSource
    private val subs = rvSampleEngine().catalog.value!!.subscriptions

    @Test
    fun `the rules come from a template, a subscription by its name, or the configuration file`() {
        assertEquals(RvRuleSource.Template(rvTemplate("cn-direct")), rvRuleSource(source.copy(template = "cn-direct"), subs))
        assertEquals(RvRuleSource.Subscription(subs.first().name), rvRuleSource(source.copy(template = null, subscriptionId = subs.first().id), subs))
        assertEquals(RvRuleSource.File, rvRuleSource(source.copy(template = null, subscriptionId = null), subs))
    }

    @Test
    fun `a template the app has no words for shows its id, and one that needs GeoSite says so`() {
        val unknown = rvTemplate("future")
        assertEquals(RvLabel.Text("future"), unknown.name)
        assertNull(unknown.line)
        assertNull(unknown.unsupported)
        assertEquals(R.string.rv_template_needs_geosite, rvTemplate("gfw", needsGeosite = true).unsupported)
        assertEquals(RvRuleSource.Template(unknown), rvRuleSource(source.copy(template = "future"), subs))
    }

    @Test
    fun `範本 lists the core's templates in its order, and a failed read says so until 重試 reads them`() = runTest {
        val engine = object : Engine by rvSampleEngine() {
            var failing = true

            override suspend fun ruleTemplates(): Result<List<RuleTemplate>> =
                if (failing) Result.failure(EngineException(ErrorCode.Busy)) else Result.success(listOf(RuleTemplate("gfw", true), RuleTemplate("cn-direct")))
        }
        val vm = TemplateViewModel(engine).also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} } }
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
        assertNull(vm.uiState.value.templates)
        assertEquals(RvRuleSource.Template(rvTemplate("cn-direct")), vm.uiState.value.source)
        engine.failing = false
        vm.retry()
        assertNull(vm.uiState.value.failure)
        assertEquals(listOf(rvTemplate("gfw", needsGeosite = true), rvTemplate("cn-direct")), vm.uiState.value.templates)
    }

    @Test
    fun `the review names the source and the template and leaves out what the core does not tell`() {
        val impact = rvTemplateImpact(RvRuleSource.File, "cn-direct")
        assertEquals(RvRuleSource.File, impact.from)
        assertEquals(rvTemplate("cn-direct"), impact.to)
        assertEquals(listOf(null, null, null, null), listOf(impact.added, impact.changed, impact.removed, impact.kept))
        assertEquals(0, impact.groups.size + impact.rules.size)
    }

    /** The sample engine with its rules from no template, its setRuleSource answered by [answer]. */
    private class Hand(var answer: Result<Unit> = Result.success(Unit)) : Engine by rvSampleEngine() {
        val calls = mutableListOf<String>()

        override suspend fun setRuleSource(template: String, options: RuleOptions): Result<Unit> {
            calls += "$template ${options.blockQuic}"
            return answer
        }
    }

    @Test
    fun `套用 sets the template with the source's options, then leaves, and a failure stays until 套用 succeeds`() = runTest {
        val engine = Hand(Result.failure(EngineException(ErrorCode.Busy)))
        val vm = TemplateImpactViewModel(engine, "cn-direct")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        var left = 0
        vm.apply { left++ }
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
        assertEquals(0, left)
        engine.answer = Result.success(Unit)
        vm.apply { left++ }
        assertNull(vm.uiState.value.failure)
        assertEquals(1, left)
        val quic = engine.catalog.value!!.ruleSource.options.blockQuic
        assertEquals(listOf("cn-direct $quic", "cn-direct $quic"), engine.calls)
    }

    private fun effective(via: RuleTarget, main: String = MAIN) =
        EffectiveConfig(Mode.Rule, main, via, listOf("1.1.1.1", "9.9.9.10"), source, "text", truncated = false)

    @Test
    fun `生效設定 maps the main group, the DNS route, every rule and the listed apps`() {
        val catalog = rvSampleEngine().catalog.value!!
        val rows = listOf(
            RuleRow("a", 0, RuleKind.DomainSuffix, "DomainSuffix", "a.example", 1, RuleTarget.Direct),
            RuleRow("b", 1, RuleKind.DomainSuffix, "DomainSuffix", "b.example", 1, RuleTarget.Reject),
        )
        val config = rvEffectiveConfig(effective(RuleTarget.Group(MAIN)), catalog, rows, 3, RV_UNKNOWN_PLACES)
        assertEquals(MAIN, config.main?.id)
        assertEquals(catalog.groups.size - 1, config.groups.size)
        assertEquals(RvLabel.Res(R.string.rv_main_node), config.dnsVia)
        assertEquals(listOf(RvRuleEnd.Direct, RvRuleEnd.Reject), config.rules.map { it.end })
        assertEquals(listOf("1.1.1.1", "9.9.9.10"), config.dns)
        assertEquals(3, config.proxiedApps)
        assertEquals(RvRuleSource.Template(rvTemplate("cn-direct")), config.source)
        assertEquals(RvLabel.Res(R.string.mode_direct), rvEffectiveConfig(effective(RuleTarget.Direct), catalog, rows, 0, RV_UNKNOWN_PLACES).dnsVia)
        assertNull(rvEffectiveConfig(effective(RuleTarget.Direct, main = "absent"), catalog, rows, 0, RV_UNKNOWN_PLACES).main)
    }

    /** The sample engine whose effectiveConfig fails [failing] times first. */
    private class Flaky(var failing: Int, private val base: FakeEngine = rvSampleEngine()) : Engine by base {
        override suspend fun effectiveConfig(): Result<EffectiveConfig> {
            if (failing > 0) {
                failing--
                return Result.failure(EngineException(ErrorCode.Busy))
            }
            return base.effectiveConfig()
        }
    }

    private fun TestScope.collected(engine: Engine) = EffectiveConfigViewModel(engine, reads = UnconfinedTestDispatcher(testScheduler)).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `生效設定 reads the core's config and text, and follows 依 App`() = runTest {
        val engine = rvSampleEngine()
        val vm = collected(engine)
        val ui = vm.uiState.value
        assertEquals(rvRouteMode(engine.effectiveConfig().getOrThrow().mode), ui.mode)
        assertEquals(engine.effectiveConfig().getOrThrow().text, ui.text)
        assertEquals(engine.rules(0, 500).getOrThrow().total, ui.config?.rules?.size)
        engine.setPerApp(PerApp(packages = setOf("a", "b")))
        assertEquals(2, vm.uiState.value.config?.proxiedApps)
    }

    @Test
    fun `a first read that failed shows why, and 重試 reads again`() = runTest {
        val vm = collected(Flaky(1))
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
        assertNull(vm.uiState.value.config)
        vm.retry()
        assertNull(vm.uiState.value.failure)
        assertNotNull(vm.uiState.value.config)
    }

    private companion object {
        val MAIN = rvSampleEngine().catalog.value!!.mainGroupId
    }
}
