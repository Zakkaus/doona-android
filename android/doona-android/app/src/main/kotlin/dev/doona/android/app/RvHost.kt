package dev.doona.android.app

import android.os.Trace
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import dev.doona.android.designsystem.rv.LocalRvNav
import dev.doona.android.designsystem.rv.LocalRvShared
import dev.doona.android.designsystem.rv.LocalRvTabEntry
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvShared

/**
 * The app's pages: [backStack] (活動 first, the page on top last) shown by Navigation 3's [NavDisplay] with RV's
 * transitions ([rvSceneMove]). Each entry gets its own saved state and ViewModels, which Navigation 3 clears only once
 * the entry is off the stack and its exit has finished; the stack itself is the caller's, saved with the activity
 * (`rememberNavBackStack`), so it survives process death. A stack restored with a page [entries] has no entry for,
 * such as one another version of the app saved, falls back to its tab's root ([keepRouted]) before anything shows.
 *
 * Back pops the top page, following the finger while a predictive back gesture runs; a cancelled gesture runs the
 * page back and pops nothing, a committed one pops once: a second back while the popped page still leaves pops
 * nothing, as that page is the one it would pop ([RvTabMoves.shown]). On a tab root back pops to 活動, which is
 * always underneath, and on 活動 back is left to the system, which leaves the app. A page that handles back itself
 * (the pull-down's picker on 活動) registers after the host, so it closes first.
 *
 * Every entry shares one [SharedTransitionLayout] ([LocalRvShared]); the floating nav shows the app's three tabs
 * ([LocalRvNav]); a tab root a hard-cut tab switch is showing gets [LocalRvTabEntry].
 */
@Composable
internal fun RvNavHost(backStack: MutableList<NavKey>, modifier: Modifier = Modifier, entries: EntryProviderScope<NavKey>.() -> Unit) {
    val motion = Rv.motion
    val shift = with(LocalDensity.current) { RvMotion.axisShift.roundToPx() }
    val provider = entryProvider(fallback = ::rvUnrouted, builder = entries)
    val moves = remember(backStack) {
        backStack.keepRouted { key -> RV_UNROUTED !in provider(key).metadata }
        RvTabMoves(backStack.toList())
    }
    moves.see(backStack.toList())
    CompositionLocalProvider(LocalRvNav provides APP_NAV) {
        SharedTransitionLayout(modifier) {
            val rv = remember(this) { rvEntryDecorator(this, moves) }
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.popShown(moves.shown) },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator(), rv),
                sharedTransitionScope = this,
                transitionSpec = { rvSceneMove(motion, shift, forward = true) },
                popTransitionSpec = { rvSceneMove(motion, shift, forward = false) },
                predictivePopTransitionSpec = { rvSceneMove(motion, shift, forward = false) },
                entryProvider = { key -> rvKeyed(key, provider(key)) },
            )
        }
    }
}

/**
 * What the host's provider gives a key [RvNavHost]'s entries do not route: an entry marked [RV_UNROUTED], so the host
 * can drop the key from a restored stack, and that fails as an unknown key always did if it is ever shown.
 */
private fun rvUnrouted(key: NavKey): NavEntry<NavKey> = NavEntry(key, metadata = mapOf(RV_UNROUTED to true)) { error("No page for $key") }

private const val RV_UNROUTED = "dev.doona.android.app.unrouted"

/** [entry] with its [key] in its metadata, so a transition can tell which page and which tab each scene shows. */
private fun rvKeyed(key: NavKey, entry: NavEntry<NavKey>): NavEntry<NavKey> = NavEntry(key, entry.contentKey, entry.metadata + (RV_NAV_KEY to key)) { entry.Content() }

/**
 * Gives each page the shared-element scopes of [layout] and its own coming and going, and [LocalRvTabEntry] to the
 * page a tab switch brings in; each page's composition is a section of its own in a Perfetto trace.
 */
private fun rvEntryDecorator(layout: SharedTransitionScope, moves: RvTabMoves) = NavEntryDecorator<NavKey> { entry ->
    val shared = RvShared(layout, LocalNavAnimatedContentScope.current, RvMotion.PUSH_MS)
    val key = rvKeyOf(entry)
    val entering = moves.tabSwitch && key == moves.top
    val move = LocalNavAnimatedContentScope.current.transition
    if (key != null && move.currentState == EnterExitState.Visible && move.targetState == EnterExitState.Visible) {
        SideEffect { moves.shown = key }
    }
    CompositionLocalProvider(LocalRvShared provides shared, LocalRvTabEntry provides entering) {
        Trace.beginSection(TRACE_PAGE)
        entry.Content()
        Trace.endSection()
    }
}

/**
 * Whether the last change of the back stack switched tabs, and the key it left on [top]. The host updates it as it
 * composes, before the page the change brings in composes; seeing the same stack again changes nothing, so a
 * composition run twice gives the same answer. A page read again later (a pop back to a tab root) is no tab switch.
 */
private class RvTabMoves(private var stack: List<NavKey>) {
    var tabSwitch = false
        private set
    val top: NavKey get() = stack.last()

    /** The page last shown on its own, every move into it finished: the page the user sees on top, which back pops. */
    var shown: NavKey? = null

    fun see(now: List<NavKey>) {
        if (now == stack) return
        tabSwitch = currentTab(now) != currentTab(stack)
        stack = now
    }
}

/**
 * Back from the host: pops the page on top once. While a popped page still leaves, [shown] is that page and no longer
 * on the stack, so a second back pops nothing rather than the page underneath; while a pushed page comes in, [shown]
 * is the page under it and back pops the new one, as on its own.
 */
private fun MutableList<NavKey>.popShown(shown: NavKey?) {
    if (shown == null || shown in this) pop(last())
}

private const val TRACE_PAGE = "RvPage"
