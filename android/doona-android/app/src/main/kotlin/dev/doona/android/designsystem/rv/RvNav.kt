package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.max
import dev.doona.android.R
import kotlinx.coroutines.launch

/** A destination of the floating bottom bar: its id (one of the `RV_NAV_*` values), glyph and label. */
internal class RvNavItem(val id: Int, @DrawableRes val icon: Int, @StringRes val label: Int)

internal const val RV_NAV_ACTIVITY = 0
internal const val RV_NAV_POLICY = 1
internal const val RV_NAV_SETTINGS = 2
internal const val RV_NAV_CONNECTIONS = 3

/** The tab homes, the same four on every page that shows the bar: 活動, 策略, 連線 and 設定 (the owner, 2026-11-23). */
internal val RV_NAV = listOf(
    RvNavItem(RV_NAV_ACTIVITY, R.drawable.rv_sym_space_dashboard, R.string.hub_activity),
    RvNavItem(RV_NAV_POLICY, R.drawable.rv_sym_account_tree, R.string.nav_policies),
    RvNavItem(RV_NAV_CONNECTIONS, R.drawable.rv_sym_sync_alt, R.string.rv_nav_connections),
    RvNavItem(RV_NAV_SETTINGS, R.drawable.rv_sym_settings, R.string.hub_settings),
)

/** The tabs the bar shows: [RV_NAV]. */
internal val LocalRvNav = staticCompositionLocalOf { RV_NAV }

/**
 * The floating bottom bar (measured §9): a glass capsule [RvChrome.navInset] in from the sides and [rvNavBottom] above
 * the screen's edge, a filled glyph over a label for each of [LocalRvNav]'s tabs, the one whose id is [selected] on a
 * flat opaque pill; a larger font makes the label's line, and so the capsule, taller ([rvNavLabelLine]), never cutting
 * a glyph. [onSelect] gets a tab's id. While [scroll], the page's, moves its content up, it shrinks to icons
 * only, 336 x 52 dp, centred and almost opaque, as Revolut's bar does. Tapping the [selected] tab again glides that page
 * back to its top in [RvMotion.TOP_MS] (G6).
 */
@Composable
internal fun RvFloatingNav(selected: Int, onSelect: (Int) -> Unit, glass: RvGlass, scroll: ScrollState, insets: WindowInsets, modifier: Modifier = Modifier) {
    val motion = Rv.motion
    val scope = rememberCoroutineScope()
    val shrink by animateFloatAsState(if (rvCollapsedOnScroll(scroll)) 1f else 0f, motion.tween(RvMotion.NAV_MS), label = "nav")
    val pick: (Int) -> Unit = { i ->
        if (i != selected) {
            onSelect(i)
        } else {
            scope.launch {
                RvFrames.begin(TO_TOP)
                try {
                    scroll.animateScrollTo(0, motion.tween(RvMotion.TOP_MS, RvMotion.Decelerate))
                } finally {
                    RvFrames.end(TO_TOP)
                }
            }
        }
    }
    val grow = rvNavLabelGrowth()
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = rvNavBottom(insets)), contentAlignment = Alignment.BottomCenter) {
        val items = LocalRvNav.current
        // The labels' one size, from the open capsule's tabs ([rvRowFit]); a label folding away may shrink alone.
        val tab = (maxWidth - RvChrome.navInset * 2 - RvChrome.navPillInset * 2) / items.size
        val labelStyle = rvRowFit(items.map { stringResource(it.label) }, Rv.type.navLabel, tab - RvNavSize.labelInset * 2)
        val width = lerp(maxWidth - RvChrome.navInset * 2, RvChrome.navCollapsedWidth, shrink)
        val height = lerp(RvChrome.navHeight + grow, RvChrome.navCollapsedHeight, shrink)
        val colors = Rv.colors
        val tint = colors.navGlass
        val rim = colors.navRim?.let { Modifier.border(RvChrome.navRim, it, CircleShape) } ?: Modifier
        Row(
            Modifier
                .size(width, height)
                .rvSoftShadow(CircleShape, RvChrome.navShadow, RvChrome.navShadowDrop, RvChrome.NAV_SHADOW_ALPHA)
                .rvGlass(glass, CircleShape, RvGlassTint(lerp(tint.over, tint.solid, shrink), tint.solid))
                .then(rim)
                .padding(RvChrome.navPillInset)
                .selectableGroup(),
        ) {
            LocalRvNav.current.forEach { item -> NavItem(item, item.id == selected, shrink, labelStyle, Modifier.weight(1f)) { pick(item.id) } }
        }
    }
}

/**
 * One destination: the glyph over its label, in the tabs' one [labelStyle], on the pill when [selected]; [shrink] fades
 * the label out and the glyph down.
 */
@Composable
private fun NavItem(item: RvNavItem, selected: Boolean, shrink: Float, labelStyle: TextStyle, modifier: Modifier, onClick: () -> Unit) {
    val colors = Rv.colors
    val ink = if (selected) colors.text else colors.textSecondary
    val pill = if (selected) Modifier.background(colors.navPill) else Modifier
    val label = stringResource(item.label)
    Column(
        modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .then(pill)
            .selectable(selected, interactionSource = null, indication = rvPressIndication(), role = Role.Tab, onClick = onClick)
            // The tab's one name, whether or not its label is composed: a collapsed bar draws icons only, and the
            // drawn label is cleared below so a screen reader does not read the name twice.
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RvGlyph(item.icon, lerp(RvChrome.navGlyph, RvChrome.navCollapsedGlyph, shrink), ink)
        val line = rvNavLabelLine()
        val fit = rvShrinkToFit(labelStyle)
        if (shrink < 1f) {
            Spacer(Modifier.height(RvChrome.navLabelGap * (1 - shrink)))
            // The label keeps its line while the capsule folds, so only its width can make it smaller: a word too long
            // for the tab (Connections at 92 of 93 dp) shrinks to fit, whole, inside the pill's round ends.
            val box = Modifier.clearAndSetSemantics {}.height(line * (1 - shrink)).alpha((1 - 2 * shrink).coerceAtLeast(0f)).padding(horizontal = RvNavSize.labelInset)
            Box(box, contentAlignment = Alignment.TopCenter) {
                RvText(
                    label,
                    labelStyle,
                    Modifier.wrapContentHeight(Alignment.Top, unbounded = true).height(line),
                    color = ink,
                    align = TextAlign.Center,
                    autoSize = fit,
                )
            }
        }
    }
}

/** How far the capsule's bottom sits above the screen's edge: 40 dp, or more above a taller system bar. */
@Composable
internal fun rvNavBottom(insets: WindowInsets): Dp {
    val bar = with(LocalDensity.current) { insets.getBottom(this).toDp() }
    return max(RvChrome.navBottom, bar + RvChrome.navAboveInset)
}

/**
 * The space every page with the capsule keeps free at its end: the capsule, as tall as the font size makes it, and
 * [RvChrome.navGap] between it and the last card once the page is scrolled to its end (item 3).
 */
@Composable
internal fun rvNavClearance(insets: WindowInsets): Dp = rvNavBottom(insets) + RvChrome.navHeight + rvNavLabelGrowth() + RvChrome.navGap

/** From the screen's edge to the open capsule's middle, as tall as the font size makes it: [RvNavFade]'s height. */
@Composable
internal fun rvNavMiddle(insets: WindowInsets): Dp = rvNavBottom(insets) + (RvChrome.navHeight + rvNavLabelGrowth()) / 2

/**
 * A label's line: the 15 sp label's line height at the phone's font size, never less than [RvChrome.navLabel], so a
 * larger font does not cut the glyphs' bottoms (ruler audit finding 1).
 */
@Composable
private fun rvNavLabelLine(): Dp = with(LocalDensity.current) { max(RvChrome.navLabel, Rv.type.navLabel.lineHeight.toDp()) }

/** What a larger font adds to the capsule's height: the label's line beyond [RvChrome.navLabel], 0 at the default size. */
@Composable
private fun rvNavLabelGrowth(): Dp = rvNavLabelLine() - RvChrome.navLabel

/**
 * Whether the capsule is collapsed: from the moment [scroll] moves the content up until it moves it back down or
 * reaches the top, as Revolut's bar follows the direction of the scroll.
 */
@Composable
private fun rvCollapsedOnScroll(scroll: ScrollState): Boolean {
    var collapsed by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        var last = scroll.value
        snapshotFlow { scroll.value }.collect { now ->
            if (now <= 0 || now < last) {
                collapsed = false
            } else if (now > last) {
                collapsed = true
            }
            last = now
        }
    }
    return collapsed
}

private const val TO_TOP = "tab-to-top"

internal object RvNavSize {
    /** A label's room inside its tab: clear of the selected pill's round ends at the label's height. */
    val labelInset = 8.dp
}
