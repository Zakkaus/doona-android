package dev.doona.android.feature.policies

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.ScoreChallenger
import dev.doona.android.engine.ScoreCounters
import dev.doona.android.engine.ScoreCoverage
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.engine.ScoreNetwork
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A group's page as the app draws it: an engine, GroupViewModel, the mapper, then the page of the s1125d sample (goldens
 * `app-rv-group-*`). On the RV sample engine it is the 串流 selector over the sample's two subscriptions, with the switch
 * off as the engine's networks agree, and on once UDP uses another node; before the engine is ready it is the skeleton
 * under the group's name. The engine's sample instant is the page's now. A Score group over the sample's Hong Kong nodes
 * ([scored]) shows what honk compared in the approved Score cards (`proto-rv-policy-group-score-*`): TCP's and UDP's with
 * the networks on two nodes, one for both on one. A group with no member says so. No place mapper is wired yet (P3c),
 * so every node is 其他.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvGroupShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE)
    fun streamingLight() = shot("group", dark = false, whole = true) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WHOLE)
    fun streamingDark() = shot("group", dark = true, whole = true) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WHOLE_FS200)
    fun streamingFs200() = shot("group", dark = false, fontScale = RV_FS200, whole = true) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WHOLE_EN)
    fun streamingEnLight() = shot("group-en", dark = false, whole = true) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WHOLE_APART)
    fun apartLight() = shot("group-apart", dark = false, whole = true) { Page(::apart) }

    @Test
    @Config(qualifiers = WHOLE_APART)
    fun apartDark() = shot("group-apart", dark = true, whole = true) { Page(::apart) }

    /** A selector over groups and nodes as a user writes it in honk by hand ([honkGroupsEngine]): its groups stand as rows. */
    @Test
    fun honkGroupsLight() = shot("group-honk", dark = false) { Page(::honkGroupsEngine, HONK_EXIT) }

    /** The first read failed: the group's name and the banner with 重試, no skeleton left standing. */
    @Test
    fun readFailedLight() = shot("group-read-failed", dark = false) { Page(::unreadable) }

    @Test
    fun loadingLight() = shot("group-loading", dark = false) { Page(::notReady) }

    @Test
    fun loadingDark() = shot("group-loading", dark = true) { Page(::notReady) }

    @Test
    @Config(qualifiers = WHOLE_SCORE)
    fun scoreLight() = shot("group-score", dark = false, whole = true) { Page({ scored(apart = true) }, SCORE) }

    @Test
    @Config(qualifiers = WHOLE_SCORE)
    fun scoreDark() = shot("group-score", dark = true, whole = true) { Page({ scored(apart = true) }, SCORE) }

    @Test
    @Config(qualifiers = WHOLE_SCORE_FS200)
    fun scoreFs200() = shot("group-score", dark = false, fontScale = RV_FS200, whole = true) { Page({ scored(apart = true) }, SCORE) }

    @Test
    @Config(qualifiers = WHOLE_SCORE_JOINT)
    fun scoreJointLight() = shot("group-score-joint", dark = false, whole = true) { Page({ scored(apart = false) }, SCORE) }

    /** 空 in [honkGroupsEngine], a selector with no member. */
    @Test
    fun emptyLight() = shot("group-empty", dark = false) { Page(::honkGroupsEngine, "g-空") }

    @Test
    fun emptyDark() = shot("group-empty", dark = true) { Page(::honkGroupsEngine, "g-空") }

    /** Score chooses the nodes, so a tap on a node opens its detail and chooses nothing. */
    @Test
    fun aTapInAScoreGroupOpensTheNodeDetail() {
        val selects = mutableListOf<String?>()
        val details = mutableListOf<String>()
        val scored = scored(apart = true)
        val engine = object : Engine by scored {
            override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
                selects += memberId
                return Result.success(Unit)
            }
        }
        compose.rvContent(dark = false) {
            val vm = remember { GroupViewModel(engine, SCORE, clock = { SAMPLE_NOW }, reads = Dispatchers.Unconfined) }
            GroupRoute(vm, SCORE, onBack = {}, onNodeDetail = { details += it })
        }
        val node = scored.groups.value.single { it.id == SCORE }.members.last()
        compose.onAllNodes(hasText(node.name))[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(node.id), details)
        assertEquals(emptyList<String?>(), selects)
    }

    /** A failed choice leaves the library's banner at the top: what failed, the engine's reason and 重試 (plan 8). */
    @Test
    fun aFailedChoiceShowsItsBannerAndRetries() {
        var calls = 0
        var answer: Result<Unit> = Result.failure(EngineException(ErrorCode.Busy))
        val sample = rvSampleEngine()
        val failing = object : Engine by sample {
            override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
                calls++
                return answer
            }
        }
        val node = sample.groups.value.single { it.id == STREAMING }.members.last().name
        compose.rvContent(dark = false) { Page({ failing }) }
        assertEquals(0, shown(text(R.string.rv_failed_switch_node)))
        // The row's own click: a tap at its centre can land under the floating 測試群組.
        compose.onAllNodes(hasText(node))[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, shown(text(R.string.rv_failed_switch_node)))
        assertEquals(1, shown(text(R.string.error_busy)))
        answer = Result.success(Unit)
        compose.onNode(hasText(text(R.string.rv_retry)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(2, calls)
        assertEquals(0, shown(text(R.string.rv_failed_switch_node)))
    }

    /** 測試群組 probes this group. */
    @Test
    fun theButtonTestsTheGroup() {
        var tested: String? = null
        val sample = rvSampleEngine()
        val engine = object : Engine by sample {
            override suspend fun testLatency(groupId: String?): Result<Unit> {
                tested = groupId
                return Result.success(Unit)
            }
        }
        compose.rvContent(dark = false) { Page({ engine }) }
        compose.onNode(hasText(text(R.string.rv_policy_test_group)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(STREAMING, tested)
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun shot(screen: String, dark: Boolean, fontScale: Float = 1f, whole: Boolean = false, content: @Composable () -> Unit) {
        compose.rvContent(dark, fontScale, content = content)
        if (whole) compose.assertWhole()
        compose.drawFully()
        val variant = when {
            fontScale != 1f -> "fs${(fontScale * PERCENT).toInt()}"
            dark -> "dark"
            else -> "light"
        }
        val name = "app-rv-$screen-$variant"
        compose.rvRowRules(name)
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$name.png")
    }

    /** The route on a view model with the sample's clock, reading on the test's thread. */
    @Composable
    private fun Page(engine: () -> Engine, groupId: String = STREAMING) {
        val vm = remember { GroupViewModel(engine(), groupId, clock = { SAMPLE_NOW }, reads = Dispatchers.Unconfined) }
        GroupRoute(vm, groupId, onBack = {})
    }

    /** The sample engine with UDP on another node than TCP, so the switch starts on. */
    private fun apart(): FakeEngine = rvSampleEngine().apply {
        val members = groups.value.single { it.id == STREAMING }.members
        runBlocking { select(STREAMING, members.last().id, Network.Udp) }
    }

    /** The sample engine whose nodes' details cannot be read. */
    private fun unreadable(): Engine = object : Engine by rvSampleEngine() {
        override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> = Result.failure(EngineException(ErrorCode.Busy))
    }

    /**
     * The sample engine with a Score group over its Hong Kong nodes and what honk compared, as the approved Score sample
     * has it: TCP on the fastest, compared with 4 of 6, one challenger of each relation; UDP, [apart], on the second,
     * compared with 2 of 6, else on TCP's node.
     */
    private fun scored(apart: Boolean): Engine {
        val sample = rvSampleEngine()
        val hk = sample.groups.value.single { it.id == "g-HK" }.members
        val nodes = hk.sortedBy { it.latencyMs ?: Int.MAX_VALUE }
        val udp = if (apart) nodes[1] else nodes[0]
        val evidence = ScoreGroup(
            SCORE,
            hk.map { it.id },
            tcp = network(nodes[0], ScoreCoverage(hk.size, 4, 0), listOf(nodes[1] to "equivalent", nodes[2] to "selectedFaster", nodes[3] to "challengerFaster")),
            udp = network(udp, ScoreCoverage(hk.size, 2, 0), listOf((if (apart) nodes[0] else nodes[1]) to "equivalent")),
        )
        val group = Group(SCORE, "Hong Kong", GroupKind.Score, hk, Group.Selection(nodes[0].id, false), Group.Selection(udp.id, false))
        sample.groups.value += group
        return object : Engine by sample {
            override suspend fun scoreState(): Result<ScoreState> = Result.success(ScoreState(0, listOf(evidence)))
        }
    }

    private fun network(chosen: Member, coverage: ScoreCoverage, challengers: List<Pair<Member, String>>) = ScoreNetwork(
        chosen.name,
        chosen.id,
        "observedUsable",
        "none",
        coverage,
        challengers.map { (node, relation) -> ScoreChallenger(node.name, node.id, "commonTargets", relation, 4, 30_000) },
        ScoreCounters(0, 0, 0, 0),
    )

    /** The sample engine before it is ready: the page waits as a skeleton. */
    private fun notReady(): FakeEngine = rvSampleEngine().apply { ready.value = false }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100
        const val SAMPLE_NOW = 1_795_343_040_000L
        const val STREAMING = "g-Streaming"
        const val SCORE = "g-Hong Kong"

        /** Windows as tall as each page whole; measured by assertWhole. */
        const val WHOLE = "+h3289dp"
        const val WHOLE_FS200 = "+h6403dp"
        const val WHOLE_EN = "en-w412dp-h3248dp-notnight-xhdpi"
        const val WHOLE_APART = "+h3495dp"
        const val WHOLE_SCORE = "+h1993dp"
        const val WHOLE_SCORE_FS200 = "+h3384dp"
        const val WHOLE_SCORE_JOINT = "+h1720dp"
    }
}
