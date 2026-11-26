package dev.doona.android.feature.policies

import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPlace
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
import dev.doona.android.feature.model.NodePlace
import dev.doona.android.feature.model.NodePlaces
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
}

/** Every node under 其他, its full name standing for the short one. */
private val NOWHERE = NodePlaces { NodePlace(RvPlace.Other, null, it) }
