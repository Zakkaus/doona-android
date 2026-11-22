package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvOutboundPart
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.data.rvPercents

/**
 * 活動's outbound card: the rule list (C1, the default), or one of four alternatives the owner compares
 * (plans/briefs/rv-outbound-variants.md). Each starts with the mode row, which opens the mode picker, ends in 規則 with
 * 查看全部, shows the main node first and lets every outbound open its group. [Share] (C2) stacks a bar by today's bytes
 * over a legend; [Chips] (C3) keeps the main node's row and puts the others in chips that scroll sideways; [Routes] (C4)
 * joins what matches to where it goes; [Tiles] (C5) gives the main node a wide tile and the others a grid.
 */
internal enum class RvOutboundVariant { List, Share, Chips, Routes, Tiles }

/**
 * The outbounds an alternative shows in [mode], most bytes first after the main node: in 規則 the main node, the two rule
 * groups that sent the most today and the rest folded into 其他; in 全域 the one node with the day's bytes; none in 直連.
 */
internal fun rvOutboundParts(mode: RvRouteMode, nodeId: String?): List<RvOutboundPart> = when (mode) {
    RvRouteMode.Direct -> emptyList()

    RvRouteMode.Global -> listOf(RvOutboundPart(null, nodeId, RvData.today.total))

    RvRouteMode.Rule -> buildList {
        add(RvOutboundPart(null, nodeId, RvData.mainBytes))
        RvData.ruleOutbounds.take(RULE_ROWS).forEach { add(RvOutboundPart(it.group, it.nodeId, it.bytes)) }
        val rest = RvData.ruleOutbounds.drop(RULE_ROWS)
        if (rest.isNotEmpty()) add(RvOutboundPart(null, null, rest.sumOf { it.bytes }, folded = rest.size))
    }
}

/** Where a part leads: its rule group; the main node's group, 節點, which picks it; for 其他, every rule. */
internal fun RvActivityRoutes.open(part: RvOutboundPart): () -> Unit = when {
    part.other -> rules
    else -> part.group?.let { group -> { rule(group) } } ?: nodes
}

/** A part's place: the node's name, 直連, or 其他. */
@Composable
internal fun RvOutboundPart.place(): String = when {
    other -> stringResource(R.string.rv_outbound_other)
    else -> nodeId?.let { RvData.node(it)?.name } ?: stringResource(R.string.mode_direct)
}

/** What a part is for: its rule group, 主節點 when rules send the rest to it, how many rules 其他 holds; nothing in 全域. */
@Composable
internal fun RvOutboundPart.role(mode: RvRouteMode): String? = when {
    other -> pluralStringResource(R.plurals.rv_rule_count, folded, folded)
    group != null -> stringResource(group)
    else -> stringResource(R.string.rv_main_node).takeIf { mode == RvRouteMode.Rule }
}

/** A small tile's or chip's two lines: a rule group over its place, 其他 over how many rules it holds. */
@Composable
private fun RvOutboundPart.lines(mode: RvRouteMode): Pair<String, String?> = if (other || group == null) place() to role(mode) else role(mode).orEmpty() to place()

/** [parts]' colours: the accent's steps for the nodes, the main node first, the control grey for direct, paler for 其他. */
@Composable
@ReadOnlyComposable
internal fun rvPartColors(parts: List<RvOutboundPart>): List<Color> {
    val colors = Rv.colors
    var node = 0
    return parts.map {
        when {
            it.other -> colors.outboundOther
            it.direct -> colors.outboundDirect
            else -> colors.outbounds[minOf(node++, colors.outbounds.lastIndex)]
        }
    }
}

/** A part's mark at [size]: the node's flag (the main node's with the check), 直連's arrow, or 其他's dots, on [fill]. */
@Composable
internal fun RvPartMark(part: RvOutboundPart, size: Dp, ring: Color = Rv.colors.card, fill: Color = Rv.colors.discOnCard) {
    val node = part.nodeId?.let { RvData.node(it) }
    when {
        node != null -> RvFlag(node.place.name, size, current = part.main, ring = ring)

        part.other -> Box(Modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            RvGlyph(R.drawable.rv_sym_more_horiz, size * MARK_GLYPH, Rv.colors.text)
        }

        else -> RvDirectDisc(size, fill = fill)
    }
}

/** An alternative outbound card ([RvOutboundVariant]) in place of the rule list. */
@Composable
internal fun RvOutboundVariantCard(variant: RvOutboundVariant, state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes) {
    RvCard {
        RvModeRow(mode, routes.mode)
        if (mode == RvRouteMode.Direct) {
            RvDirectNote()
            return@RvCard
        }
        val parts = rvOutboundParts(mode, state.node?.id)
        when (variant) {
            RvOutboundVariant.List -> Unit
            RvOutboundVariant.Share -> ShareParts(parts, mode, routes)
            RvOutboundVariant.Chips -> ChipParts(parts, state, mode, routes)
            RvOutboundVariant.Routes -> RvRouteMap(parts, mode, routes)
            RvOutboundVariant.Tiles -> TileParts(parts, state, mode, routes)
        }
        if (mode == RvRouteMode.Rule) RvSeeAllRules(routes.rules) else Spacer(Modifier.height(RvSpace.tight))
    }
}

/**
 * C2 · Share bar: one bar stacked by today's bytes per outbound under the mode row, then a legend row for each, its swatch
 * the bar's colour: the main node first and larger, with its flag, name and role; the others with their route
 * (`串流 → Taipei 04`), share and bytes.
 */
@Composable
private fun ShareParts(parts: List<RvOutboundPart>, mode: RvRouteMode, routes: RvActivityRoutes) {
    val format = rememberRvFormat()
    val colors = rvPartColors(parts)
    val percents = rvPercents(parts.map { it.bytes })
    Row(
        Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvOutboundSize.barBelow).fillMaxWidth().height(RvOutboundSize.bar).clip(CircleShape),
        horizontalArrangement = Arrangement.spacedBy(RvOutboundSize.barGap),
    ) {
        parts.forEachIndexed { i, part -> if (part.bytes > 0) Box(Modifier.weight(part.bytes.toFloat()).fillMaxHeight().background(colors[i])) }
    }
    parts.forEachIndexed { i, part ->
        val share = format.percent(percents[i] / PERCENT)
        val bytes = format.bytes(part.bytes).toString()
        if (part.main) {
            val sub = listOfNotNull(part.role(mode), share).joinToString(" · ")
            LegendRow(colors[i], { RvPartMark(part, RvSize.rowDisc) }, part.place(), sub, bytes, null, routes.open(part))
        } else {
            val (first, second) = part.lines(mode)
            val route = listOfNotNull(first, second).joinToString(if (part.other) " · " else " → ")
            LegendRow(colors[i], { RvPartMark(part, RvOutboundSize.mark) }, route, null, bytes, share, routes.open(part))
        }
    }
}

/**
 * A legend row: [mark], then the [swatch] before the [title] (with [sub] under it, the row two lines tall), then
 * [bytes], after [share] when given.
 */
@Composable
private fun LegendRow(swatch: Color, mark: @Composable () -> Unit, title: String, sub: String?, bytes: String, share: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (sub == null) RvSize.rowOneLine else RvSize.rowTwoLine)
            .rvPress(RectangleShape, Role.Button, onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        mark()
        Spacer(Modifier.width(RvSpace.rowGap))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(RvOutboundSize.swatch).clip(RoundedCornerShape(RvOutboundSize.swatchRadius)).background(swatch))
                Spacer(Modifier.width(RvSpace.subGap))
                RvText(title, if (sub == null) Rv.type.rowSub else Rv.type.rowTitle)
            }
            sub?.let { RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary) }
        }
        share?.let {
            RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary)
            Spacer(Modifier.width(RvSpace.trailingGap))
        }
        RvText(bytes, Rv.type.rowValue, align = TextAlign.End)
    }
}

/**
 * C3 · Chips: the main node's row as on the rule list, then the other outbounds as chips in one row that scrolls
 * sideways, each with its mark, its group and its place with today's bytes.
 */
@Composable
private fun ChipParts(parts: List<RvOutboundPart>, state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes) {
    MainNodeRow(parts.first(), state, mode, routes)
    val others = parts.drop(1)
    if (others.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, top = RvSpace.tight),
        horizontalArrangement = Arrangement.spacedBy(RvOutboundSize.chipGap),
    ) {
        others.forEach { OutboundChip(it, mode, routes.open(it)) }
    }
}

/** The main node's row: its flag with the check, its name, its role, and its latency over its change; it opens 節點. */
@Composable
private fun MainNodeRow(part: RvOutboundPart, state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes) {
    val node = state.node
    val listed = node?.let { RvData.node(it.id) }
    RvRow({ RvPartMark(part, RvSize.rowDisc) }, part.place(), part.role(mode) ?: stringResource(R.string.nav_nodes), onClick = routes.open(part)) {
        RvLatencyChange(node?.latencyMs?.takeUnless { node.failed }, listed?.changeMs?.takeUnless { node.failed })
    }
}

@Composable
private fun OutboundChip(part: RvOutboundPart, mode: RvRouteMode, onClick: () -> Unit) {
    val format = rememberRvFormat()
    Row(
        Modifier
            .height(RvOutboundSize.chip)
            .clip(CircleShape)
            .background(Rv.colors.discOnCard)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics(mergeDescendants = true) {}
            .padding(start = RvOutboundSize.chipStart, end = RvOutboundSize.chipEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvSpace.trailingGap),
    ) {
        RvPartMark(part, RvOutboundSize.mark, ring = Rv.colors.discOnCard, fill = Rv.colors.card)
        val (first, second) = part.lines(mode)
        Column {
            RvText(first, Rv.type.pill)
            RvText(listOfNotNull(second, format.bytes(part.bytes).toString()).joinToString(" · "), Rv.type.tileCaption, color = Rv.colors.textSecondary)
        }
    }
}

/**
 * C5 · Tiles: the main node as a tile across the card with its flag, name, role, today's bytes, latency and change;
 * under it a two-column grid of smaller tiles, one per other outbound, each with its mark, group, place and bytes.
 */
@Composable
private fun TileParts(parts: List<RvOutboundPart>, state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes) {
    val format = rememberRvFormat()
    val shape = RoundedCornerShape(RvOutboundSize.tileRadius)
    val tile = Modifier.clip(shape).background(Rv.colors.page)
    Column(Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.tight), verticalArrangement = Arrangement.spacedBy(RvOutboundSize.tileGap)) {
        val main = parts.first()
        val node = state.node
        val listed = node?.let { RvData.node(it.id) }
        Row(tile.fillMaxWidth().rvPress(shape, Role.Button, routes.open(main)).padding(RvSpace.rowPadding), verticalAlignment = Alignment.CenterVertically) {
            RvPartMark(main, RvSize.rowDisc, ring = Rv.colors.page)
            Spacer(Modifier.width(RvSpace.rowGap))
            Column(Modifier.weight(1f)) {
                RvText(main.place(), Rv.type.rowTitle)
                RvText(listOfNotNull(main.role(mode), format.bytes(main.bytes).toString()).joinToString(" · "), Rv.type.rowSub, color = Rv.colors.textSecondary)
            }
            RvLatencyChange(node?.latencyMs?.takeUnless { node.failed }, listed?.changeMs?.takeUnless { node.failed })
        }
        parts.drop(1).chunked(TILE_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(RvOutboundSize.tileGap)) {
                row.forEach { part ->
                    Column(tile.weight(1f).rvPress(shape, Role.Button, routes.open(part)).semantics(mergeDescendants = true) {}.padding(RvOutboundSize.tilePad)) {
                        RvPartMark(part, RvOutboundSize.mark, ring = Rv.colors.page, fill = Rv.colors.card)
                        Spacer(Modifier.height(RvOutboundSize.tilePad))
                        val (first, second) = part.lines(mode)
                        RvText(first, Rv.type.pill)
                        second?.let { RvText(it, Rv.type.tileCaption, color = Rv.colors.textSecondary) }
                        Spacer(Modifier.height(RvSpace.tight))
                        RvText(format.bytes(part.bytes).toString(), Rv.type.rowValue)
                    }
                }
                repeat(TILE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val PERCENT = 100.0
private const val TILE_COLUMNS = 2

/** A glyph fills this share of its mark, as a row disc's does (22 of 40 dp). */
private const val MARK_GLYPH = 0.55f

/** Each outbound card, for the preview pane. */
internal class RvOutboundVariantPreviews : PreviewParameterProvider<RvOutboundVariant> {
    override val values = RvOutboundVariant.entries.asSequence()
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvOutboundVariantPreview(@PreviewParameter(RvOutboundVariantPreviews::class) outbound: RvOutboundVariant) {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, outbound = outbound) }
}
