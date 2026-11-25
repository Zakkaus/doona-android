package dev.doona.android.app

import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The predictive back's preview ([rvSceneBack], [rvBackPreview]) as [RvNavHost] plays it: the page on top shrinks toward
 * [RvMotion.BACK_SCALE] and moves away from the swipe's edge, the commit goes on from there without a jump, and at
 * animator scale 0 nothing moves.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class RvBackPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var back: OnBackPressedDispatcher

    @Composable
    private fun Host() {
        RvTheme(rvSystemScheme(false), false) {
            val backStack = rememberNavBackStack(ActivityKey, PoliciesKey, GroupKey("g"))
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RvNavHost(backStack, Modifier.fillMaxSize()) {
                entry<ActivityKey> { Page("activity") }
                entry<PoliciesKey> { Page("policies") }
                entry<GroupKey> { key -> Page("group ${key.groupId}") }
            }
        }
    }

    /** A page filling the screen, [name] at its top left, so the page's scale and shift show in where the name is. */
    @Composable
    private fun Page(name: String) = Box(Modifier.fillMaxSize()) { BasicText(name) }

    private fun start() {
        compose.setContent { Host() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun swipe(edge: Int) {
        val x = if (edge == BackEventCompat.EDGE_LEFT) EDGE_X else width() - EDGE_X
        compose.runOnIdle {
            back.dispatchOnBackStarted(BackEventCompat(x, EDGE_Y, 0f, edge))
            back.dispatchOnBackProgressed(BackEventCompat(x, EDGE_Y, HALF, edge))
        }
        frames(PIPELINE_FRAMES + 2)
    }

    @Test
    fun aSwipeFromTheLeftShrinksThePageAndMovesItRight() = preview(BackEventCompat.EDGE_LEFT, away = 1)

    @Test
    fun aSwipeFromTheRightShrinksThePageAndMovesItLeft() = preview(BackEventCompat.EDGE_RIGHT, away = -1)

    private fun preview(edge: Int, away: Int) {
        start()
        val rest = page()
        swipe(edge)
        val now = page()
        val scale = now.width / rest.width
        assertTrue("the page shrinks well past S1's 96 %: $scale", scale < 0.95f && scale >= RvMotion.BACK_SCALE - 0.001f)
        // The shrink and the shift are seeked on the same curve: a share of the shrink is as large a share of the shift.
        val share = (1f - scale) / (1f - RvMotion.BACK_SCALE)
        val shift = away * share * (width().toInt() / RvMotion.BACK_SHIFT_PARTS - margin())
        val expected = width() / 2 * (1f - scale) + shift
        assertTrue("the page moved $away to $expected: ${now.left}", abs(now.left - expected) <= SLACK_PX)
    }

    /**
     * NavDisplay (Navigation 3 1.2.2) finishes a committed predictive back on the preview's own transition, whatever
     * popTransitionSpec says (measured: a pop spec sliding both pages half a screen changed no frame). This holds it
     * there across an upgrade: a commit that switched to the plain pop's zoom would grow the page back toward 96 %.
     */
    @Test
    fun aCommittedSwipeGoesOnFromThePreview() {
        start()
        swipe(BackEventCompat.EDGE_LEFT)
        val held = page()
        compose.runOnIdle { back.onBackPressed() }
        var last = held
        repeat(PIPELINE_FRAMES + 2) {
            frames(1)
            if (shown("group g") == 0) return@repeat
            val now = page()
            assertTrue("the page grew back: ${last.width} to ${now.width}", now.width <= last.width + 1f)
            assertTrue("the page slid back toward the edge: ${last.left} to ${now.left}", now.left >= last.left - 1f)
            last = now
        }
        frames(SETTLE_FRAMES)
        assertEquals(0, shown("group g"))
        assertEquals(1, shown("policies"))
    }

    @Test
    fun atAnimatorScaleZeroThePreviewMovesNothing() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        start()
        val rest = page()
        swipe(BackEventCompat.EDGE_LEFT)
        if (shown("group g") == 1) assertEquals(rest, page())
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        frames(2)
        assertEquals(rest, page())
    }

    /** Where the top page's name is drawn, in px. */
    private fun page(): Rect = compose.onAllNodesWithText("group g").fetchSemanticsNodes().single().boundsInRoot

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun width(): Float = compose.onRoot().fetchSemanticsNode().size.width.toFloat()

    private fun margin(): Float = with(compose.density) { RvMotion.backMargin.roundToPx().toFloat() }

    private fun frames(n: Int) = repeat(n) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private companion object {
        const val PIPELINE_FRAMES = 3
        const val SETTLE_FRAMES = 30
        const val EDGE_X = 4f
        const val EDGE_Y = 800f
        const val HALF = 0.5f

        /** Rounding, and the shift applied inside or outside the page's scale. */
        const val SLACK_PX = 4f
    }
}
