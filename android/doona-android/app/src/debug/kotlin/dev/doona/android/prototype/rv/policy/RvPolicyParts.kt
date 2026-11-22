package dev.doona.android.prototype.rv.policy

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvAlpha
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvChrome
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvGlass
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.nodes.RvNodesSize
import dev.doona.android.prototype.rv.nodes.RvNodesType
import dev.doona.android.prototype.rv.rvGlass
import dev.doona.android.prototype.rv.rvPress
import dev.doona.android.prototype.rv.rvSoftShadow
import kotlin.math.max
import kotlin.math.min

/** The 策略 pages' own sizes; everything else is the shared tokens. */
internal object RvPolicySize {
    /** The chevron's distance from the column before it. */
    val chevronGap = 4.dp
}

/** The share of a line a row's title keeps, if it needs it, before the end of the line takes the rest. */
private const val TITLE_SHARE = 0.4f

/** The tag on a card whose data needs a later core: rules arrive after v0.1. */
internal const val RV_V03 = "v0.3"

/**
 * [dev.doona.android.prototype.rv.RvRow] with a two-line trailing column, measured line by line: [title] with
 * [titleEnd] at its end, then [subtitle] with [subtitleEnd]. It draws what RvRow draws (the leading disc or flag, the
 * title over the subtitle, the trailing value over its second line, the chevron); only the widths differ. RvRow gives
 * its trailing column the width of its widest line beside both lines, which leaves `自動測速 · 48 個節點` no room beside
 * `Hong Kong 02 IPLC`; here each line shares its width only with its own end. Local variant (STATE: fold into RvRow).
 */
@Composable
internal fun RvPolicyRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    titleEnd: (@Composable () -> Unit)? = null,
    subtitleEnd: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = RvSize.rowTwoLine).padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(RvSpace.rowGap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
            Line(titleEnd) { RvText(title, Rv.type.rowTitle) }
            Line(subtitleEnd) { RvText(subtitle, Rv.type.rowSub, color = Rv.colors.textSecondary) }
        }
        if (chevron) {
            Spacer(Modifier.width(RvPolicySize.chevronGap))
            RvChevron()
        }
    }
}

/**
 * One line of [RvPolicyRow]: the start keeps its own width up to [TITLE_SHARE] of the line, the end takes what it
 * needs of the rest, and the start then gets what the end leaves; either ends in an ellipsis only when both are long.
 */
@Composable
private fun Line(end: (@Composable () -> Unit)?, start: @Composable () -> Unit) {
    Layout({
        start()
        end?.invoke()
    }) { parts, constraints ->
        val width = constraints.maxWidth
        val gap = if (parts.size > 1) RvSpace.trailingGap.roundToPx() else 0
        val keep = min(parts[0].maxIntrinsicWidth(constraints.maxHeight), (width * TITLE_SHARE).toInt())
        val endPart = parts.getOrNull(1)?.measure(Constraints(maxWidth = max(0, width - keep - gap)))
        val endWidth = endPart?.width ?: 0
        val startPart = parts[0].measure(Constraints(maxWidth = max(0, width - endWidth - gap)))
        val height = max(startPart.height, endPart?.height ?: 0)
        layout(width, height) {
            startPart.place(0, (height - startPart.height) / 2)
            endPart?.let { it.place(width - it.width, (height - it.height) / 2) }
        }
    }
}

/**
 * Where a rule or a picked target sends traffic, as 活動's rule rows draw it: the flag of the node the group uses, or
 * 直連's straight arrow.
 */
@Composable
internal fun RvPolicyTarget(group: RvPolicyGroup?) {
    if (group == null) RvIconDisc(R.drawable.rv_sym_arrow_right_alt) else RvFlag(group.selected.place.name, RvSize.rowDisc)
}

/**
 * The top bar's actions on the 策略 tab: 44 dp glass discs at the bar's end, on the row and in the 48 dp targets of
 * [dev.doona.android.prototype.rv.RvTopBar]. Local variant of RvChrome's private `GlassButton` (STATE: share it).
 */
@Composable
internal fun RvPolicyBarActions(glass: RvGlass, insets: WindowInsets, actions: List<Triple<Int, String, () -> Unit>>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .windowInsetsPadding(insets.only(WindowInsetsSides.Top))
            .padding(top = RvChrome.barTop - RvChrome.touchPad, end = RvSpace.gutter - RvChrome.touchPad),
        horizontalArrangement = Arrangement.spacedBy(RvChrome.buttonGap - RvChrome.touchPad * 2),
    ) {
        actions.forEach { (icon, label, onClick) -> GlassDisc(glass, icon, label, onClick) }
    }
}

@Composable
private fun GlassDisc(glass: RvGlass, @DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val rim = Rv.colors.controlRim?.let { Modifier.border(RvChrome.navRim, it, CircleShape) } ?: Modifier
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(RvChrome.control)
            .rvSoftShadow(CircleShape, RvChrome.controlShadow, RvChrome.controlShadowDrop, RvChrome.CONTROL_SHADOW_ALPHA)
            .rvGlass(glass, CircleShape, Rv.colors.controlGlass)
            .then(rim)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(icon, RvChrome.controlGlyph, Rv.colors.text) }
}

/**
 * Node detail's floating 'Get started' pill alone, without its round companion: the page's one action, 16 dp above the
 * inset, the cards scrolling under it. Local variant of [dev.doona.android.prototype.rv.nodes.RvFloatingActions]
 * (STATE: make its companion optional).
 */
@Composable
internal fun RvPolicyFloatingPill(label: String, onClick: () -> Unit, insets: WindowInsets, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val fill = if (colors.dark) Color.White else colors.text
    Column(modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = RvNodesSize.cta)
                .rvSoftShadow(CircleShape, RvNodesSize.ctaShadow, RvNodesSize.ctaShadowDrop, RvAlpha.CTA_SHADOW)
                .clip(CircleShape)
                .background(fill)
                .rvPress(CircleShape, Role.Button, onClick)
                .padding(vertical = RvSpace.tight),
            contentAlignment = Alignment.Center,
        ) { RvText(label, RvNodesType.cta, color = colors.page, align = TextAlign.Center) }
        Spacer(Modifier.height(RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** The room a page keeps under its last card for a floating pill: the gap, the pill and its 16 dp over the inset. */
internal val rvPillRoom = RvSpace.gutter + RvNodesSize.cta + RvSpace.gutter

/** What a rule matches, as its row's title. */
@Composable
internal fun RvRuleMatch.title(): String = when (this) {
    is RvRuleMatch.Domains -> pluralStringResource(R.plurals.rv_policy_domains, count, first, count)
    is RvRuleMatch.GeoIp -> code
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest)
}

/** The kind of match, under the title. */
@Composable
internal fun RvRuleMatch.kind(): String = when (this) {
    is RvRuleMatch.Domains -> stringResource(R.string.rv_policy_kind_domain)
    is RvRuleMatch.GeoIp -> GEOIP
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan_kind)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest_kind)
}

private const val GEOIP = "GEOIP"

/** The 策略 tab's two bar actions: test every group, update the subscriptions. */
@Composable
internal fun rvPolicyActions(onTestAll: () -> Unit, onUpdate: () -> Unit) = listOf(
    Triple(R.drawable.rv_sym_speed, stringResource(R.string.rv_policy_test_all), onTestAll),
    Triple(R.drawable.rv_stats_ic_sync, stringResource(R.string.rv_policy_update_subscriptions), onUpdate),
)
