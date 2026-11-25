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
 * The outbound card (item 24), in the 最快節點 card's rows, four at most: the mode, which opens the mode picker; then in
 * 規則 the main node and the two rule groups that sent the most traffic today, and 查看全部 for the rest; in 全域 the one
 * node; in 直連 a line that no node is in use.
 */
@Composable
internal fun OutboundCard(state: RvActivityState, mode: RvRouteMode, routes: RvActivityRoutes, nodes: List<RvNode>, rules: List<RvActivityRule>, cards: RvActivityCards) {
    RvCard {
        RvRow({ RvIconDisc(R.drawable.rv_sym_directions) }, stringResource(R.string.act_mode), stringResource(mode.label), onClick = routes.mode) { RvChevron() }
        if (mode == RvRouteMode.Direct) {
            RvChoiceLine(stringResource(R.string.rv_direct_note), Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding))
            return@RvCard
        }
        // A node switch cross-fades the row to the new flag and name.
        Crossfade(state.node, animationSpec = Rv.motion.tween(RvMotion.FADE_MS), label = "main node") { node ->
            val listed = node?.let { at -> nodes.firstOrNull { it.id == at.id } }
            RvRow(
                // The node in use: its flag carries the current node's check.
                { RvFlag(listed?.place?.flag, RvSize.rowDisc, current = node != null) },
                node?.name ?: RV_NO_VALUE,
                stringResource(if (mode == RvRouteMode.Rule) R.string.rv_main_node else R.string.nav_nodes),
                onClick = routes.node,
                titleKeepWords = false,
                // Its latency over the change since the test before, as 節點 and the 最快節點 rows show it (L-12).
            ) { RvLatencyChange(node?.latencyMs?.takeUnless { node.failed }, listed?.changeMs?.takeUnless { node.failed }) }
        }
        if (mode != RvRouteMode.Rule) return@RvCard
        rules.take(RULE_ROWS).forEach { RuleRow(it, cards, routes) }
        RvSeeAll(pluralStringResource(R.plurals.rv_see_all_rules, cards.ruleCount, cards.ruleCount), routes.rules)
    }
}

/** The glyph of how a group picks ([kind]), as 策略 draws it; a selector's for a group 策略 does not list. */
private fun groupGlyph(kind: RvGroupKind?): Int = (kind ?: RvGroupKind.Select).glyph

/**
 * A rule group that sent traffic today: the node's flag, or for 直連 its own straight arrow (the diamond is the mode's), the
 * group (直連 when the rules send there themselves), its node, its bytes; a tap opens the group, or the rules for 直連. A group whose UDP uses another node (a sample) names both, a line each, each after its
 * network (`TCP Taipei 04`, `UDP Hong Kong 01`); when the two are in different regions no one flag stands for them, and
 * the group's glyph (how it picks, as 策略 draws the group) leads the row.
 */
@Composable
private fun RuleRow(rule: RvActivityRule, cards: RvActivityCards, routes: RvActivityRoutes) {
    val format = rememberRvFormat()
    val node = rule.nodeId?.let(cards.node)
    val udp = rule.udpNodeId?.takeIf { it != rule.nodeId }?.let(cards.node)
    val line = when {
        node == null -> stringResource(R.string.mode_direct)

        udp == null -> node.name

        else -> stringResource(R.string.rv_network_node, stringResource(R.string.ui_tcp), node.name) + "\n" +
            stringResource(R.string.rv_network_node, stringResource(R.string.ui_udp), udp.name)
    }
    RvRow(
        {
            when {
                node == null -> RvIconDisc(R.drawable.rv_sym_arrow_right_alt)
                udp != null && udp.place != node.place -> RvIconDisc(groupGlyph(rule.groupId?.let(cards.groupKind)))
                else -> RvFlag(node.place.flag, RvSize.rowDisc)
            }
        },
        rule.label ?: stringResource(R.string.mode_direct),
        line,
        onClick = { rule.groupId?.let(routes.rule) ?: routes.rules() },
        titleKeepWords = false,
        subtitleKeepWords = false,
    ) { RvText(format.bytes(rule.bytes).toString(), Rv.type.rowValue, align = TextAlign.End) }
}

/** The outbound card lists two rule groups after the main node. */
internal const val RULE_ROWS = 2
