package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalRvShimmerAt
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonEnd
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.prototype.rv.gallery.RvGallerySkeleton
import dev.doona.android.testing.drawFully
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 活動, 策略 (in each of its modes) and 訂閱 before the engine is ready: a skeleton of each page's cards ([RvSkeletonCards];
 * on 活動 also of the hero's lines), under the real header, bar and nav. Goldens `proto-rv-<page>-loading-light|dark|fs115|fs200` at
 * animator scale 0 (the shimmer stands still), the library's parts as the gallery shows them (`proto-rv-skeleton-*`),
 * and per page (策略 in each mode, and 依 App) a frame strip of the content replacing its skeleton
 * (`proto-rv-<page>-loading-frames-light`: the skeleton, then [AFTER_LOAD] ms after the data came, each time under its
 * frame) and one of the shimmer's band. A skeleton row is as tall as a row whose text keeps one line; that every part
 * lands where its skeleton stood is [RvSkeletonBoundsTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSkeletonTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun activityLight() = shot(ACTIVITY, dark = false)

    @Test fun activityDark() = shot(ACTIVITY, dark = true)

    @Test fun activityFs115() = shot(ACTIVITY, fontScale = RV_FS115)

    @Test fun activityFs200() = shot(ACTIVITY, fontScale = RV_FS200)

    @Test fun policyLight() = shot(POLICY, dark = false)

    @Test fun policyDark() = shot(POLICY, dark = true)

    @Test fun policyFs115() = shot(POLICY, fontScale = RV_FS115)

    @Test fun policyFs200() = shot(POLICY, fontScale = RV_FS200)

    @Test fun policyGlobalLight() = shot(POLICY_GLOBAL, dark = false)

    @Test fun policyGlobalDark() = shot(POLICY_GLOBAL, dark = true)

    @Test fun policyGlobalFs115() = shot(POLICY_GLOBAL, fontScale = RV_FS115)

    @Test fun policyGlobalFs200() = shot(POLICY_GLOBAL, fontScale = RV_FS200)

    @Test fun policyDirectLight() = shot(POLICY_DIRECT, dark = false)

    @Test fun policyDirectDark() = shot(POLICY_DIRECT, dark = true)

    @Test fun policyDirectFs115() = shot(POLICY_DIRECT, fontScale = RV_FS115)

    @Test fun policyDirectFs200() = shot(POLICY_DIRECT, fontScale = RV_FS200)

    @Test fun subsLight() = shot(SUBS, dark = false)

    @Test fun subsDark() = shot(SUBS, dark = true)

    @Test fun subsFs115() = shot(SUBS, fontScale = RV_FS115)

    @Test fun subsFs200() = shot(SUBS, fontScale = RV_FS200)

    @Test
    fun partsLight() {
        rvAnimatorScale(0f)
        compose.rvShot("skeleton", dark = false) { Parts() }
    }

    @Test
    fun partsDark() {
        rvAnimatorScale(0f)
        compose.rvShot("skeleton", dark = true) { Parts() }
    }

    @Test fun activityFrames() = frames(ACTIVITY)

    @Test fun policyFrames() = frames(POLICY)

    @Test fun subsFrames() = frames(SUBS)

    @Test fun policyGlobalFrames() = frames(POLICY_GLOBAL)

    @Test fun policyDirectFrames() = frames(POLICY_DIRECT)

    @Test fun perAppFrames() = frames(PER_APP)

    @Test fun aSkeletonRowIsAsTallAsItsRow() = rowHeights(1f)

    @Test fun aSkeletonRowIsAsTallAsItsRowFs200() = rowHeights(RV_FS200)

    /**
     * The shimmer's band at points of its pass ([LocalRvShimmerAt]: a test's clock does not run an endless animation),
     * as a strip (`proto-rv-skeleton-shimmer-light`): before the page's start the parts are as at rest; half way it
     * lightens the parts under it.
     */
    @Test
    fun theShimmersBandCrossesTheSkeleton() {
        val at = mutableStateOf(0f)
        compose.rvContent(dark = false) { CompositionLocalProvider(LocalRvShimmerAt provides at.value) { Parts() } }
        val shots = SHIMMER_AT.map { pass ->
            at.value = pass
            compose.waitForIdle()
            "pass ${(pass * PERCENT).roundToInt()} %" to capture()
        }
        rvStrip(shots, shots.first().second.height, dark = false).captureRoboImage("$SHOTS/proto-rv-skeleton-shimmer-light.png")
        val pixels = shots.map { it.second.toPixelMap().buffer }
        assertFalse("the band leaves the parts as they were half way across", pixels[0].contentEquals(pixels[HALF_WAY]))
    }

    private fun shot(page: String, dark: Boolean = false, fontScale: Float = 1f) {
        rvAnimatorScale(0f)
        compose.rvShot("$page-loading", dark, fontScale = fontScale) { Page(page, loading = true) }
    }

    /** The skeleton, then the data coming in at a time the test sets, and frames of the cross-fade on its clock. */
    private fun frames(page: String) {
        val loading = mutableStateOf(true)
        compose.rvContent(dark = false) { Page(page, loading.value) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val clock = compose.mainClock
        val shots = mutableListOf("skeleton" to capture())
        loading.value = false
        val loaded = clock.currentTime
        AFTER_LOAD.forEach { at ->
            clock.advanceTimeBy(loaded + at - clock.currentTime)
            shots += "loaded +${clock.currentTime - loaded} ms" to capture()
        }
        val first = shots.first().second
        rvStrip(shots, first.height, dark = false).captureRoboImage("$SHOTS/proto-rv-$page-loading-frames-light.png")
    }

    /** Real rows (a two-line row, one with an end, a one-line row; in Han and in Latin) over their skeletons, as tall. */
    private fun rowHeights(fontScale: Float) {
        rvAnimatorScale(0f)
        compose.rvContent(dark = false, fontScale) {
            Column {
                RvCard {
                    RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, HAN_TITLE, HAN_SUBTITLE)
                    RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, LATIN_TITLE, LATIN_SUBTITLE) { RvText(LATIN_END, Rv.type.rowValue) }
                    RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, HAN_TITLE, null)
                }
                RvSkeletonCard {
                    RvSkeletonRow(0)
                    RvSkeletonRow(1, end = RvSkeletonEnd.Value)
                    RvSkeletonRow(2, subtitle = null)
                }
            }
        }
        compose.waitForIdle()
        val rows = compose.parts().getValue(RvPart.ROW)
        val real = rows.take(ROWS)
        val skeleton = rows.drop(ROWS)
        val drift = mutableListOf<String>()
        real.zip(skeleton).forEachIndexed { i, (row, bars) -> same(drift, "row $i's height", bars.height, row.height) }
        assertTrue("at $fontScale a skeleton row is not as tall as its row:\n" + drift.joinToString("\n"), drift.isEmpty())
    }

    private fun same(drift: MutableList<String>, what: String, actual: Float, expected: Float) {
        if (abs(actual - expected) > TOLERANCE) drift += "$what: $actual dp with the skeleton, $expected dp with the content"
    }

    private fun capture(): ImageBitmap {
        compose.drawFully()
        return compose.onRoot().captureToImage()
    }

    /** The tagged cards and rows, each in root dp, top to bottom. */
    private fun ComposeContentTestRule.parts(): Map<String, List<Rect>> = listOf(RvPart.CARD, RvPart.ROW).associateWith { tag ->
        onAllNodesWithTag(tag).fetchSemanticsNodes().map { node ->
            val d = node.layoutInfo.density.density
            Rect(node.positionInRoot / d, node.size.toSize() / d)
        }.filter { it.left >= 0f }.sortedWith(compareBy({ it.top }, { it.left }))
    }

    @Composable
    private fun Parts() {
        Column(Modifier.fillMaxSize().background(Rv.colors.page).padding(top = TOP)) { RvGutter { RvGallerySkeleton() } }
    }

    @Composable
    private fun Page(page: String, loading: Boolean) = RvSkeletonPage(page, loading)

    private companion object {
        val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val ACTIVITY = RvSkeletonPages.ACTIVITY
        const val POLICY = RvSkeletonPages.POLICY
        const val SUBS = RvSkeletonPages.SUBS
        const val PER_APP = RvSkeletonPages.PER_APP
        const val POLICY_GLOBAL = RvSkeletonPages.POLICY_GLOBAL
        const val POLICY_DIRECT = RvSkeletonPages.POLICY_DIRECT

        /**
         * About a third and two thirds into the cross-fade ([RvMotion.HERO_FADE_MS]), which starts some frames after the
         * data comes, once the content is composed and measured; then well past its end.
         */
        val AFTER_LOAD = listOf(176L, 288L, 800L)

        /** Before the page, a quarter, half and three quarters of the way across. */
        val SHIMMER_AT = listOf(0f, 0.25f, 0.5f, 0.75f)
        const val HALF_WAY = 2
        const val PERCENT = 100
        const val TOLERANCE = 0.5f
        val TOP = 72.dp

        const val ROWS = 3
        const val HAN_TITLE = "自動"
        const val HAN_SUBTITLE = "手動選擇"
        const val LATIN_TITLE = "Taipei 04"
        const val LATIN_SUBTITLE = "VLESS"
        const val LATIN_END = "26 ms"
    }
}
