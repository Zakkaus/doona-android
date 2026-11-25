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
import dev.doona.android.engine.Network
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
 * under the group's name. The engine's sample instant is the page's now. No place mapper is wired yet (P3c), so every
 * node is 其他.
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

    @Test
    fun loadingLight() = shot("group-loading", dark = false) { Page(::notReady) }

    @Test
    fun loadingDark() = shot("group-loading", dark = true) { Page(::notReady) }

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

    /** The sample engine before it is ready: the page waits as a skeleton. */
    private fun notReady(): FakeEngine = rvSampleEngine().apply { ready.value = false }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100
        const val SAMPLE_NOW = 1_795_343_040_000L
        const val STREAMING = "g-Streaming"

        /** Windows as tall as each page whole; measured by assertWhole. */
        const val WHOLE = "+h3206dp"
        const val WHOLE_FS200 = "+h6234dp"
        const val WHOLE_EN = "en-w412dp-h3205dp-notnight-xhdpi"
        const val WHOLE_APART = "+h3412dp"
    }
}
