package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.max
import dev.doona.android.R

/** A destination of the floating bottom bar. */
internal class RvNavItem(@DrawableRes val icon: Int, @StringRes val label: Int)

/** The tab homes, the same on every page that shows the bar: 活動, 策略 and 設定. */
internal val RV_NAV = listOf(
    RvNavItem(R.drawable.rv_sym_space_dashboard, R.string.hub_activity),
    RvNavItem(R.drawable.rv_sym_account_tree, R.string.nav_policies),
    RvNavItem(R.drawable.rv_sym_settings, R.string.hub_settings),
)
internal const val RV_NAV_ACTIVITY = 0
internal const val RV_NAV_SETTINGS = 2

/**
 * The floating bottom bar (measured §9): a glass capsule [RvChrome.navInset] in from the sides and [rvNavBottom] above
 * the screen's edge, a filled glyph over a label for each of [RV_NAV], the [selected] one on a flat opaque pill.
 * [collapsed] shrinks it to icons only, 336 x 52 dp, centred and almost opaque, as Revolut's bar does while the content
 * scrolls up.
 */
@Composable
internal fun RvFloatingNav(selected: Int, onSelect: (Int) -> Unit, glass: RvGlass, collapsed: Boolean, insets: WindowInsets, modifier: Modifier = Modifier) {
    val shrink by animateFloatAsState(if (collapsed) 1f else 0f, tween(RvChrome.COLLAPSE_MS), label = "nav")
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = rvNavBottom(insets)), contentAlignment = Alignment.BottomCenter) {
        val width = lerp(maxWidth - RvChrome.navInset * 2, RvChrome.navCollapsedWidth, shrink)
        val height = lerp(RvChrome.navHeight, RvChrome.navCollapsedHeight, shrink)
        val colors = Rv.colors
        val tint = colors.navGlass
        val rim = colors.navRim?.let { Modifier.border(RvChrome.navRim, it, CircleShape) } ?: Modifier
        Row(
            Modifier
                .size(width, height)
                .rvSoftShadow(CircleShape, RvChrome.navShadow, RvChrome.navShadowDrop, RvChrome.NAV_SHADOW_ALPHA)
                .rvGlass(glass, CircleShape, RvGlassTint(lerp(tint.over, tint.solid, shrink), tint.solid))
                .then(rim)
                .padding(RvChrome.navPillInset),
        ) {
            RV_NAV.forEachIndexed { i, item -> NavItem(item, i == selected, shrink, Modifier.weight(1f)) { onSelect(i) } }
        }
    }
}

/** One destination: the glyph over its label on the pill when [selected]; [shrink] fades the label out and the glyph down. */
@Composable
private fun NavItem(item: RvNavItem, selected: Boolean, shrink: Float, modifier: Modifier, onClick: () -> Unit) {
    val colors = Rv.colors
    val ink = if (selected) colors.text else colors.textSecondary
    val pill = if (selected) Modifier.background(colors.navPill) else Modifier
    Column(
        modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .then(pill)
            .selectable(selected, interactionSource = null, indication = ripple(color = colors.stateLayer), role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RvGlyph(item.icon, lerp(RvChrome.navGlyph, RvChrome.navCollapsedGlyph, shrink), ink)
        val label = stringResource(item.label)
        if (shrink < 1f) {
            Spacer(Modifier.height(lerp(RvChrome.navLabelGap, 0.dp, shrink)))
            RvText(
                label,
                Rv.type.navLabel,
                Modifier.height(lerp(RvChrome.navLabel, 0.dp, shrink)).alpha((1 - 2 * shrink).coerceAtLeast(0f)),
                color = ink,
                align = TextAlign.Center,
            )
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
 * The space every page with the capsule keeps free at its end: the capsule, and [RvChrome.navGap] between it and the
 * last card once the page is scrolled to its end (item 3).
 */
@Composable
internal fun rvNavClearance(insets: WindowInsets): Dp = rvNavBottom(insets) + RvChrome.navHeight + RvChrome.navGap

/**
 * Whether the capsule is collapsed: from the moment [scroll] moves the content up until it moves it back down or
 * reaches the top, as Revolut's bar follows the direction of the scroll.
 */
@Composable
internal fun rvCollapsedOnScroll(scroll: ScrollState): Boolean {
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
