package dev.doona.android.feature.activity

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.LocalRvCovered
import dev.doona.android.designsystem.rv.RV_GHOST_DOWN
import dev.doona.android.designsystem.rv.RV_GHOST_UP
import dev.doona.android.designsystem.rv.RV_NAV_ACTIVITY
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAction
import dev.doona.android.designsystem.rv.RvAmount
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvChoiceLine
import dev.doona.android.designsystem.rv.RvDelta
import dev.doona.android.designsystem.rv.RvDotMotion
import dev.doona.android.designsystem.rv.RvDots
import dev.doona.android.designsystem.rv.RvEmptyState
import dev.doona.android.designsystem.rv.RvFadeThrough
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvHeader
import dev.doona.android.designsystem.rv.RvHero
import dev.doona.android.designsystem.rv.RvHeroSwipe
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvInline
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvNavFade
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvPageEndRoom
import dev.doona.android.designsystem.rv.RvPullLook
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSeries
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonCards
import dev.doona.android.designsystem.rv.RvSkeletonEnd
import dev.doona.android.designsystem.rv.RvSkeletonFade
import dev.doona.android.designsystem.rv.RvSkeletonHero
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonSeeAll
import dev.doona.android.designsystem.rv.RvSkeletonTiles
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSparkTile
import dev.doona.android.designsystem.rv.RvStatsDots
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.RvTileRow
import dev.doona.android.designsystem.rv.RvTopBar
import dev.doona.android.designsystem.rv.figure
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rememberRvGlass
import dev.doona.android.designsystem.rv.rvActionRowFit
import dev.doona.android.designsystem.rv.rvGlassSource
import dev.doona.android.designsystem.rv.rvHeroArt
import dev.doona.android.designsystem.rv.rvMapPan
import dev.doona.android.designsystem.rv.rvNavMiddle
import dev.doona.android.designsystem.rv.rvPullBlur
import dev.doona.android.designsystem.rv.rvShade
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.fastest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest

/**
 * The 活動 screen in Revolut's home structure (#11, #15): a header with the connection state, the download rate as the
 * hero figure and the upload rate under it on a dot map of the world that marks the node's country (#07, #08), four round
 * actions, then the outbound card (item 24: the mode, the main node and the rule groups that sent traffic today),
 * download and upload as two of Revolut's square home tiles (either opens the traffic page), and three more cards as
 * Revolut's home goes on: 今日用量, 訂閱 and 最快節點. Over the content sit the glass top bar (measured §4) and the
 * floating glass bottom bar (§9), which shrinks while the content scrolls up. It renders [RvActivityState] with its
 * mode, the rule groups that sent traffic today ([rules]), and the lower cards read [cards]. A [banner] heads the cards
 * ([RvGutter]'s slot); with no subscription, [noSubscription] puts the empty state with 新增訂閱 (which runs it) in their
 * place, as on 策略. While [loading] (before the engine is ready)
 * the hero's three lines and the cards are skeletons of themselves ([RvSkeletonHero], [ActivitySkeleton]), and the
 * content fades in over them in place; the map, the actions, the bar and the nav are there from the start. [navFade] (a
 * sample for the owner, off by default) lays [RvNavFade] under the nav. Without [nav] the page leaves its floating nav
 * out, for the old tab frame that draws its own; the routing hand-off removes it with that frame.
 */
@Composable
internal fun RvActivityScreen(
    state: RvActivityState,
    onAction: (ActivityAction) -> Unit,
    onConnect: () -> Unit,
    routes: RvActivityRoutes,
    nodes: List<RvNode>,
    subscriptions: List<RvSubscription>,
    pull: RvPullLook = RvPullLook.None,
    hero: RvHeroSwipe = RvHeroSwipe.Off,
    banner: RvNotice? = null,
    noSubscription: (() -> Unit)? = null,
    loading: Boolean = false,
    navFade: Boolean = false,
    rules: List<RvActivityRule>,
    cards: RvActivityCards,
    nav: Boolean = true,
) {
    val mode = state.mode
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    val glass = rememberRvGlass()
    // While the pull or the picker covers 活動 the map's pulse rests and the tiles' lines hold, so 活動 does not change
    // under the blur.
    val covered = remember(pull) { derivedStateOf { pull.progress() > 0f } }
    val resting = remember(covered) { { covered.value } }
    // A second's tick changes the rates and the minute's samples, nothing else. The hero's figures read them through
    // [ticking] and the traffic tiles take [state]; the rest of 活動 takes [steady], equal from one tick to the next, so a
    // tick recomposes those two and skips the rest. It recomposed the whole page each second: 7.5 ms on the phone.
    val ticking = rememberUpdatedState(state)
    val steady = state.steady()
    // The map stretches with the pull, and what lies under it moves down as far.
    val pushed = remember(pull) { pull.stretch?.let { stretch -> Modifier.graphicsLayer { translationY = stretch() } } }
    val motion = remember(pull, pushed, resting) { HeaderMotion(pull.stretch, pushed, resting) }
    val look = remember(hero, nodes, loading) { HeroLook(hero, nodes, loading) }
    val fastest = remember(nodes) { nodes.fastest(FASTEST) }
    CompositionLocalProvider(LocalRvCovered provides resting) {
        Box(Modifier.fillMaxSize()) {
            // The pull blurs and fades the page; the bars stay sharp, so the search pill, the picker's anchor, can grow.
            Column(Modifier.fillMaxSize().rvPullBlur(pull.progress).rvGlassSource(glass).background(Rv.colors.page).verticalScroll(scroll)) {
                Header(steady, ticking, mode, motion, insets, look, cards.node) { HeroActions(steady, onAction, onConnect, routes) }
                RvGutter(pushed ?: Modifier, banner) {
                    if (noSubscription != null) {
                        RvEmptyState(
                            stringResource(R.string.rv_empty_subscription),
                            stringResource(R.string.rv_empty_subscription_line),
                            stringResource(R.string.rv_add_subscription),
                            noSubscription,
                        )
                    } else {
                        RvSkeletonCards(loading, { ActivitySkeleton(mode, steady.node?.name ?: RV_NO_VALUE) }) {
                            OutboundCard(steady, mode, routes, nodes, rules, cards)
                            TrafficTiles(state, routes.traffic)
                            RvUsageCard(cards.today, cards.nowHour, cards.yesterdayByNow)
                            RvSubscriptionsCard(subscriptions, cards.nowEpochMs, routes.subscriptions)
                            RvFastestCard(fastest, steady.node?.id ?: cards.mainNodeId, routes.nodes)
                        }
                    }
                }
                RvPageEndRoom(RvPageEnd.Nav, insets)
            }
            RvTopBar(glass, insets, rvShade(scroll), onSearch = routes.search, onStats = routes.stats, Modifier.align(Alignment.TopCenter), pull, routes.subscriptions)
            if (navFade) RvNavFade(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(rvNavMiddle(insets)))
            if (nav) RvFloatingNav(RV_NAV_ACTIVITY, routes.tab, glass, scroll, insets, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Where 活動 leads: a traffic tile, 統計, the search page (the search pill), 節點 (最快節點's header), the node row, the
 * nav's tabs, the mode picker, a rule row (by its group), the full list of rules, the latency test, 訂閱 (the top bar's
 * disc and the card's header) and the round 更多 action.
 */
internal class RvActivityRoutes(
    val traffic: () -> Unit,
    val stats: () -> Unit,
    val search: () -> Unit,
    val nodes: () -> Unit,
    val node: () -> Unit,
    val tab: (Int) -> Unit,
    val mode: () -> Unit,
    val rule: (String) -> Unit,
    val rules: () -> Unit,
    val test: () -> Unit,
    val subscriptions: () -> Unit,
    val more: () -> Unit,
)

/**
 * The pull: [stretch] lengthens the map, [pushed] moves the header's text and everything under it down as far, and the
 * map's pulse rests while [resting].
 */
internal class HeaderMotion(val stretch: (() -> Float)?, val pushed: Modifier?, val resting: () -> Boolean)

/** Whether the hero [swipe]s to the main node, one of [nodes] (H1), and whether it waits for its figures ([loading]). */
internal class HeroLook(val swipe: RvHeroSwipe, val nodes: List<RvNode>, val loading: Boolean)

/** The 最快節點 card lists the first three of the 節點 page's fastest. */
private const val FASTEST = 3

internal val RvRouteMode.label: Int
    get() = when (this) {
        RvRouteMode.Rule -> R.string.rv_mode_rule
        RvRouteMode.Global -> R.string.mode_global
        RvRouteMode.Direct -> R.string.mode_direct
    }
