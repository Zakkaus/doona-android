package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.components.Tone
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvMinuteShare
import dev.doona.android.prototype.rv.data.RvRouteMode

/**
 * 活動's hero: the dot map (the default), or one of four alternatives the owner compares (plans/briefs/rv-hero-variants.md),
 * each over the same figure and the same four round actions. [Mix] (V1) splits a band by the last minute's outbounds;
 * [Wave] (V2) fills the hero with the last minute's traffic, which a finger scrubs; [Ring] (V3) sets the outbounds round
 * the duck; [Quiet] (V4) has no picture, a block tinted by the connection and chips for the outbounds in use.
 */
internal enum class RvHeroVariant { Map, Mix, Wave, Ring, Quiet }

/** An alternative hero ([RvHeroVariant]) in place of the dot map; [actions] are the four round actions. */
@Composable
internal fun RvVariantHeader(hero: RvHeroVariant, state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes, insets: WindowInsets, actions: @Composable () -> Unit) {
    when (hero) {
        RvHeroVariant.Map -> Unit
        RvHeroVariant.Mix -> MixHero(state, mode, routes, insets, actions)
        RvHeroVariant.Wave -> RvWaveHero(state, mode, insets, actions)
        RvHeroVariant.Ring -> RvRingHero(state, mode, routes, insets, actions)
        RvHeroVariant.Quiet -> QuietHero(state, mode, routes, insets, actions)
    }
}

/** The outbounds that carried the last minute in [mode]: none while disconnected, the node alone in 全域, all direct in 直連. */
internal fun rvMinuteShares(state: ActivityUiState, mode: RvRouteMode): List<RvMinuteShare> = when {
    state.status !is Status.Running -> emptyList()
    mode == RvRouteMode.Rule -> RvData.minuteShares
    mode == RvRouteMode.Global -> listOf(RvMinuteShare(null, state.node?.id, ALL))
    else -> listOf(RvMinuteShare(null, null, ALL))
}

/** Where a share leads: its rule group; for the main node, 節點, which picks it; with everything direct, the mode picker. */
internal fun RvActivityRoutes.open(share: RvMinuteShare): () -> Unit = share.group?.let { group -> { rule(group) } } ?: if (share.direct) mode else nodes

/** A share's node by its short label (`HK 02`), or 直連. */
@Composable
internal fun RvMinuteShare.name(): String = nodeId?.let { RvData.node(it)?.short } ?: stringResource(R.string.mode_direct)

/** What a share is for: its rule group, or 主節點 when rules send the rest to it; nothing in 全域 or 直連. */
@Composable
internal fun RvMinuteShare.role(mode: RvRouteMode): String? = group?.let { stringResource(it) } ?: stringResource(R.string.rv_main_node).takeIf { mode == RvRouteMode.Rule }

/** [shares]' colours in order: the accent's steps for the nodes, the main node first, and the control grey for direct. */
@Composable
@ReadOnlyComposable
internal fun rvShareColors(shares: List<RvMinuteShare>): List<Color> {
    val colors = Rv.colors
    var node = 0
    return shares.map { if (it.direct) colors.outboundDirect else colors.outbounds[minOf(node++, colors.outbounds.lastIndex)] }
}

/** 直連's mark where a flag would be: the straight arrow on a disc of [size] in [fill]. */
@Composable
internal fun RvDirectDisc(size: Dp, modifier: Modifier = Modifier, fill: Color = Rv.colors.discOnCard) {
    Box(modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        RvGlyph(R.drawable.rv_sym_arrow_right_alt, size * DIRECT_GLYPH, Rv.colors.text)
    }
}

/** A share's flag, or 直連's disc, at [size]; the main node's flag carries the current node's check. */
@Composable
internal fun RvShareMark(share: RvMinuteShare, size: Dp, ring: Color, fill: Color = Rv.colors.discOnCard) {
    val node = share.nodeId?.let { RvData.node(it) }
    if (node != null) RvFlag(node.place.name, size, current = share.group == null, ring = ring) else RvDirectDisc(size, fill = fill)
}

/** A variant hero's column: the status bar's height, [content], then the usual gap to the first card. */
@Composable
internal fun RvHeroColumn(insets: WindowInsets, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        content()
        Spacer(Modifier.height(RvSpace.actionsToCard))
    }
}

/**
 * V1 · Outbound mix: the figure where the map's hero has it, then one band split by the last minute's outbounds, each
 * part labelled with its route and share under it; a part opens its group. 全域 fills the band with its one node;
 * disconnected, the band is an empty track.
 */
@Composable
private fun MixHero(state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes, insets: WindowInsets, actions: @Composable () -> Unit) {
    RvHeroColumn(insets) {
        Spacer(Modifier.height(RvSpace.headerTop))
        RvHeroFigure(state, mode)
        Spacer(Modifier.height(RvHeroSize.mixAbove))
        OutboundMix(rvMinuteShares(state, mode), mode, routes)
        Spacer(Modifier.height(RvHeroSize.mixBelow))
        actions()
    }
}

@Composable
private fun OutboundMix(shares: List<RvMinuteShare>, mode: RvRouteMode, routes: RvActivityRoutes) {
    val format = rememberRvFormat()
    val colors = rvShareColors(shares)
    val band = Modifier.fillMaxWidth().height(RvHeroSize.band).clip(CircleShape)
    Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), horizontalArrangement = Arrangement.spacedBy(RvHeroSize.bandGap)) {
        if (shares.isEmpty()) Box(Modifier.weight(1f).then(band).background(Rv.colors.segmentTrack))
        shares.forEachIndexed { i, share ->
            // 直連 is the route itself; a node is named by its role and its short label.
            val label = if (share.direct) share.name() else listOfNotNull(share.role(mode), share.name()).joinToString(" ")
            val percent = format.percent(share.percent / ALL.toDouble())
            Column(
                Modifier
                    .weight(share.percent.toFloat())
                    .rvPress(RoundedCornerShape(RvHeroSize.bandLabel), Role.Button, routes.open(share))
                    .semantics(mergeDescendants = true) { contentDescription = "$label $percent" }
                    .padding(bottom = RvSpace.tight),
            ) {
                Box(band.background(colors[i]))
                Spacer(Modifier.height(RvHeroSize.bandLabel))
                RvText(label, Rv.type.pill)
                RvText(percent, Rv.type.pill, color = Rv.colors.textSecondary)
            }
        }
    }
}

/**
 * V4 · Quiet type: no picture. The hero is a flat block tinted by the connection, the accent while connected, the
 * warning hue while the main node's latency is degraded, a neutral step when off; under the figure a chip for each
 * outbound in use, which opens its group.
 */
@Composable
private fun QuietHero(state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes, insets: WindowInsets, actions: @Composable () -> Unit) {
    val colors = Rv.colors
    val shares = rvMinuteShares(state, mode)
    val node = state.node
    val slow = mode != RvRouteMode.Direct && (node == null || node.failed || latencyTone(node.latencyMs) != Tone.Ok)
    val tint = when {
        state.status !is Status.Running -> colors.heroOff
        slow -> colors.heroWarn
        else -> colors.heroOn
    }
    val foot = RoundedCornerShape(bottomStart = RvHeroSize.quietRadius, bottomEnd = RvHeroSize.quietRadius)
    Column(Modifier.fillMaxWidth().padding(bottom = RvSpace.cardGap).clip(foot).background(tint), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(RvSpace.headerTop))
        RvHeroFigure(state, mode)
        if (shares.isEmpty()) {
            Spacer(Modifier.height(RvSpace.heroToActions))
        } else {
            Spacer(Modifier.height(RvHeroSize.chipsAbove))
            Row(Modifier.padding(horizontal = RvSpace.gutter), horizontalArrangement = Arrangement.spacedBy(RvHeroSize.chipGap)) {
                shares.forEach { OutboundChip(it, routes.open(it)) }
            }
            Spacer(Modifier.height(RvHeroSize.chipsBelow))
        }
        actions()
        Spacer(Modifier.height(RvHeroSize.quietBottom))
    }
}

/** An outbound in use as a raised chip: its flag or 直連's disc and its short label. */
@Composable
private fun OutboundChip(share: RvMinuteShare, onClick: () -> Unit) {
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(RvHeroSize.chip)
            .rvRaised(CircleShape)
            .rvPress(CircleShape, Role.Button, onClick)
            .padding(start = RvHeroSize.chipStart, end = RvHeroSize.chipEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvHeroSize.chipStart),
    ) {
        RvShareMark(share, RvHeroSize.chipFlag, ring = Rv.colors.raised)
        RvText(share.name(), Rv.type.action)
    }
}

/** A share of everything, in percent. */
private const val ALL = 100

/** 直連's arrow fills this share of its disc, as a row disc's glyph does (22 of 40 dp). */
private const val DIRECT_GLYPH = 0.55f

/** Each hero, for the preview pane. */
internal class RvHeroVariantPreviews : PreviewParameterProvider<RvHeroVariant> {
    override val values = RvHeroVariant.entries.asSequence()
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvHeroVariantPreview(@PreviewParameter(RvHeroVariantPreviews::class) hero: RvHeroVariant) {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, hero = hero) }
}
