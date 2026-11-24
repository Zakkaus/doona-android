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
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.gallery.RvGallerySkeleton
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import dev.doona.android.prototype.rv.settings.RvPerAppActions
import dev.doona.android.prototype.rv.settings.RvPerAppScreen
import dev.doona.android.prototype.rv.settings.rvPerAppSample
import dev.doona.android.prototype.rv.subs.RvSubsActions
import dev.doona.android.prototype.rv.subs.RvSubsPage
import dev.doona.android.prototype.rv.subs.RvSubsState
import dev.doona.android.testing.drawFully
import org.junit.After
import org.junit.Assert.assertEquals
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
 * and per page a frame strip of the content replacing its skeleton (`proto-rv-<page>-loading-frames-light`: the
 * skeleton, then [AFTER_LOAD] ms after the data came, each time under its frame) and one of the shimmer's band.
 *
 * No jump: on each page, and on the per-app list, the content lands where its skeleton stood. At 1.0 every skeleton card
 * and row has the top (and every row the height) of the card or row that replaces it; at 2.0, where the content's text
 * wraps as long as it is, the first card replaced keeps its top, and a skeleton row is as tall as a row whose text keeps
 * one line.
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

    @Test fun activityKeepsItsPlaces() = places(ACTIVITY, 1f)

    @Test fun activityKeepsItsPlacesFs200() = places(ACTIVITY, RV_FS200)

    @Test fun policyKeepsItsPlaces() = places(POLICY, 1f)

    @Test fun policyKeepsItsPlacesFs200() = places(POLICY, RV_FS200)

    @Test fun policyGlobalKeepsItsPlaces() = places(POLICY_GLOBAL, 1f)

    @Test fun policyGlobalKeepsItsPlacesFs200() = places(POLICY_GLOBAL, RV_FS200)

    @Test fun policyDirectKeepsItsPlaces() = places(POLICY_DIRECT, 1f)

    @Test fun policyDirectKeepsItsPlacesFs200() = places(POLICY_DIRECT, RV_FS200)

    @Test fun subsKeepsItsPlaces() = places(SUBS, 1f)

    @Test fun subsKeepsItsPlacesFs200() = places(SUBS, RV_FS200)

    @Test fun perAppKeepsItsPlaces() = places(PER_APP, 1f)

    @Test fun perAppKeepsItsPlacesFs200() = places(PER_APP, RV_FS200)

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

    /** The cards and rows with the skeleton, then with the content in its place: where they stand, in dp. */
    private fun places(page: String, fontScale: Float) {
        rvAnimatorScale(0f)
        val loading = mutableStateOf(true)
        compose.rvContent(dark = false, fontScale) { Page(page, loading.value) }
        compose.waitForIdle()
        val skeleton = compose.parts()
        loading.value = false
        compose.waitForIdle()
        val loaded = compose.parts()
        val drift = mutableListOf<String>()
        val cards = skeleton.getValue(RvPart.CARD)
        // The skeleton stands for the page's own cards, every one of them, before any is compared with what replaces it.
        assertEquals("$page's cards while it loads", SKELETON_CARDS.getValue(page), cards.size)
        // At 2.0 only the first card replaced keeps its place: under it the content's text wraps as long as it is.
        val kept = if (fontScale == 1f) cards.size else (if (page == PER_APP) PER_APP_CARDS_ABOVE else 0) + 1
        assertTrue("$page has fewer cards loaded than its skeleton", loaded.getValue(RvPart.CARD).size >= cards.size)
        cards.take(kept).zip(loaded.getValue(RvPart.CARD)).forEachIndexed { i, (card, real) -> same(drift, "card $i's top", card.top, real.top) }
        if (fontScale == 1f) {
            skeleton.getValue(RvPart.ROW).zip(loaded.getValue(RvPart.ROW)).forEachIndexed { i, (row, real) ->
                same(drift, "row $i's top", row.top, real.top)
                same(drift, "row $i's height", row.height, real.height)
            }
        }
        assertTrue("$page at $fontScale moves when its content comes:\n" + drift.joinToString("\n"), drift.isEmpty())
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
                    RvSkeletonRow(1, end = true)
                    RvSkeletonRow(2, subtitle = false)
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
    private fun Page(page: String, loading: Boolean) {
        when (page) {
            ACTIVITY -> RvActivityScreen(rvIdleActivity(), onAction = {}, onConnect = {}, loading = loading)
            POLICY -> RvPolicyScreen(RvPolicyState(), onChange = {}, loading = loading)
            POLICY_GLOBAL -> RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Global), onChange = {}, loading = loading)
            POLICY_DIRECT -> RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Direct), onChange = {}, loading = loading)
            SUBS -> RvSubsPage(RvSubsState(), RvSubsActions(), loading = loading)
            else -> RvPerAppScreen(rvPerAppSample(if (loading) "loading" else ""), RvPerAppActions(), onBack = {})
        }
    }

    private companion object {
        val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val ACTIVITY = "activity"
        const val POLICY = "policy"
        const val SUBS = "subs"
        const val PER_APP = "perapp"
        const val POLICY_GLOBAL = "policy-global"
        const val POLICY_DIRECT = "policy-direct"

        /**
         * The cards each page shows while it loads, the cards of the content they stand for: 活動's rules card and its two
         * tiles; 策略's mode card, then under 規則 the main node's and the groups', under 全域 a subscription's nodes, under
         * 直連 none; 訂閱's two subscriptions; 依 App's policies card (real) over its list's.
         */
        val SKELETON_CARDS = mapOf(ACTIVITY to 3, POLICY to 3, POLICY_GLOBAL to 2, POLICY_DIRECT to 1, SUBS to 2, PER_APP to 2)

        /** The per-app page's policies card stands over its list, loaded or not. */
        const val PER_APP_CARDS_ABOVE = 1

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
