package dev.doona.android.prototype.rv

import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sample's gestures (brief rv-gestures), on a clock the test drives where they move: the stretch overscroll, which
 * every supported Android has (活動's pull-down still wins at the top over the stretch: [RvPullTest]); tapping the tab
 * already shown glides its page back to the top; a held disc grows by about 4 % and lightens, and goes back on release,
 * at once at animator scale 0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvGestureTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun animatorScaleBack() = rvAnimatorScale(1f)

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun fromAndroid12ScrollingStretches() = assertNotNull("the platform's stretch", overscroll())

    @Test
    fun tappingTheShownTabGlidesBackToTheTop() {
        compose.setContent { RvPreviewApp(RvMotionOptions(), RvSampleStart()) }
        compose.mainClock.autoAdvance = false
        val rest = heroTop()
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput {
            down(Offset(centerX, bottom - SCROLL_FROM.toPx()))
            repeat(STEPS) { moveBy(Offset(0f, -SCROLL.toPx() / STEPS), delayMillis = FRAME_MS) }
            up()
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        val scrolled = heroTop()
        assertTrue("活動 has scrolled: $scrolled from $rest", scrolled < rest - SCROLL / 2)
        compose.onNode(isSelected() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.TOP_MS / 2L)
        val half = heroTop()
        assertTrue("part way up: $half between $scrolled and $rest", half > scrolled && half < rest)
        compose.mainClock.advanceTimeBy(RvMotion.TOP_MS.toLong())
        assertEquals("at the top", rest.value, heroTop().value, NEAR)
    }

    @Test
    fun aHeldActionDiscGrowsAndComesBack() {
        activity()
        // In the scrolling page a press shows after the tap timeout, so a scroll can still take the finger.
        heldGrowsThenComesBack(actionDisc(), android.view.ViewConfiguration.getTapTimeout().toLong())
    }

    @Test
    fun aHeldGlassDiscGrowsAndComesBack() {
        activity()
        heldGrowsThenComesBack(compose.onNodeWithContentDescription(rvText(R.string.rv_stats)), 0L)
    }

    @Test
    fun atAnimatorScaleZeroADiscGrowsAtOnce() {
        rvAnimatorScale(0f)
        activity()
        val disc = compose.onNodeWithContentDescription(rvText(R.string.rv_stats))
        val rest = disc.width()
        disc.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        assertEquals("grown at once", 1 + RvMotion.DISC_GROW, disc.width() / rest, GROW_NEAR)
        disc.performTouchInput { cancel() }
    }

    /**
     * Holds [disc] down past [delayMs] and [RvMotion.PRESS_MS]: it has grown by [RvMotion.DISC_GROW]; after the finger
     * leaves (a cancel, so nothing opens) it is back to its size.
     */
    private fun heldGrowsThenComesBack(disc: SemanticsNodeInteraction, delayMs: Long) {
        val rest = disc.width()
        disc.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(delayMs + RvMotion.PRESS_MS / 2)
        val rising = disc.width() / rest
        assertTrue("it grows over PRESS_MS: $rising", rising > 1f && rising < 1 + RvMotion.DISC_GROW)
        compose.mainClock.advanceTimeBy(RvMotion.PRESS_MS + FRAMES_MS)
        assertEquals("held, it is 4 % larger", 1 + RvMotion.DISC_GROW, disc.width() / rest, GROW_NEAR)
        disc.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(RvMotion.PRESS_MS + FRAMES_MS)
        assertEquals("let go, it is back", 1f, disc.width() / rest, GROW_NEAR)
    }

    private fun activity() {
        compose.setContent { RvPreviewApp(RvMotionOptions(), RvSampleStart()) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
    }

    /** The disc of 活動's 更多 action: the clickable child of the action, whose label is merged into it. */
    private fun actionDisc() = compose.onAllNodesWithText(rvText(R.string.rv_more), useUnmergedTree = true).onFirst().onParent().onChildren().filterToOne(hasClickAction())

    /** The disc's width on screen: the bounds in root carry the layer's scale (the unclipped ones keep the layout's size), and nothing clips a disc. */
    private fun SemanticsNodeInteraction.width() = getBoundsInRoot().let { it.right - it.left }.value

    /** 活動's caption under the hero, which scrolls with the page. */
    private fun heroTop(): Dp = compose.onNodeWithText("${rvText(R.string.backend_connected)} · ${rvText(R.string.mode_global)}").getUnclippedBoundsInRoot().top

    /** The overscroll a scrolling page under [RvTheme] gets. */
    private fun overscroll(): OverscrollFactory? {
        var factory: OverscrollFactory? = null
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { factory = LocalOverscrollFactory.current } }
        compose.waitForIdle()
        return factory
    }

    private companion object {
        val SCROLL = 600.dp
        val SCROLL_FROM = 200.dp
        const val STEPS = 30
        const val FRAME_MS = 16L
        const val SETTLE_MS = 3_000L
        const val NEAR = 1.5f
        const val FRAMES_MS = 50L
        const val GROW_NEAR = 0.004f
    }
}
