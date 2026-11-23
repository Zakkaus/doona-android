package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.max
import dev.doona.android.designsystem.theme.DoonaElevation
import dev.doona.android.designsystem.theme.DoonaGlassTint
import dev.doona.android.designsystem.theme.DoonaMotion
import dev.doona.android.designsystem.theme.DoonaTheme

/** A destination of the floating nav: its filled [icon] and its [label]. */
@Immutable
class DoonaNavItem(@DrawableRes val icon: Int, val label: String)

/**
 * The floating bottom nav (measured §9): a glass capsule 16 dp in from the sides and [navBottom] above the screen's edge,
 * a filled glyph over a label for each of [items], the [selected] one on a flat opaque pill (S5-19); unselected ink is
 * the secondary text (S5-21). [collapsed] (see [rememberNavCollapsed]) shrinks it to icons only, 336 × 52 dp, centred
 * and almost opaque, as Revolut's bar does while the content scrolls on; it expands again when the content scrolls back.
 */
@Composable
fun DoonaFloatingNav(
    items: List<DoonaNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    glass: GlassBackdrop,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    insets: WindowInsets = DoonaTheme.insets,
) {
    val chrome = DoonaTheme.chrome
    val shrink by animateFloatAsState(if (collapsed) 1f else 0f, tween(DoonaMotion.NAV_COLLAPSE_MS), label = "nav")
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = navBottom(insets)), contentAlignment = Alignment.BottomCenter) {
        val width = lerp(maxWidth - chrome.navInset * 2, chrome.navCollapsedWidth, shrink)
        val height = lerp(chrome.navHeight, chrome.navCollapsedHeight, shrink)
        val colors = DoonaTheme.colors
        val tint = colors.navGlass
        val rim = colors.navRim?.let { Modifier.border(DoonaTheme.size.ring, it, CircleShape) } ?: Modifier
        Row(
            Modifier
                .layoutRole(LayoutRole.Chrome)
                .width(width)
                // Expanded, the capsule is 60 dp at least and grows with a label that a large font makes taller.
                .then(if (shrink == 0f) Modifier.heightIn(min = height).height(IntrinsicSize.Min) else Modifier.height(height))
                .softShadow(CircleShape, DoonaTheme.elevation.nav, DoonaTheme.elevation.navDrop, DoonaElevation.NAV_ALPHA)
                .glass(glass, CircleShape, DoonaGlassTint(lerp(tint.over, tint.solid, shrink), tint.solid))
                .then(rim)
                .padding(chrome.navPillInset)
                .selectableGroup(),
        ) {
            items.forEachIndexed { i, item -> NavItem(item, i == selected, shrink, Modifier.weight(1f)) { onSelect(i) } }
        }
    }
}

/**
 * One destination: the glyph over its label, on the pill when [selected]; [shrink] fades the label out and the glyph
 * down. The label is the item's name for a screen reader whether it shows or not.
 */
@Composable
private fun NavItem(item: DoonaNavItem, selected: Boolean, shrink: Float, modifier: Modifier, onClick: () -> Unit) {
    val colors = DoonaTheme.colors
    val chrome = DoonaTheme.chrome
    val ink = if (selected) colors.text else colors.textSecondary
    val pill = if (selected) Modifier.background(colors.navPill) else Modifier
    Column(
        modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .then(pill)
            .selectable(selected, interactionSource = null, indication = ripple(color = colors.stateLayer), role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = item.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DoonaGlyph(item.icon, lerp(chrome.navGlyph, chrome.navCollapsedGlyph, shrink), ink)
        if (shrink < 1f) {
            Spacer(Modifier.height(chrome.navLabelGap * (1f - shrink)))
            LineText(
                item.label,
                DoonaTheme.type.navLabel,
                Modifier
                    .then(if (shrink == 0f) Modifier.heightIn(min = chrome.navLabel) else Modifier.height(chrome.navLabel * (1f - shrink)))
                    .alpha((1 - 2 * shrink).coerceAtLeast(0f))
                    .clearAndSetSemantics {},
                color = ink,
                align = TextAlign.Center,
            )
        }
    }
}

/** How far the capsule's bottom sits above the screen's edge: 40 dp, or more above a taller system bar. */
@Composable
fun navBottom(insets: WindowInsets = DoonaTheme.insets): Dp {
    val bar = with(LocalDensity.current) { insets.getBottom(this).toDp() }
    return max(DoonaTheme.chrome.navBottom, bar + DoonaTheme.chrome.navAboveInset)
}

/**
 * The space every page with the capsule keeps free at its end (fix round 2): the capsule's bottom inset, its height, and
 * 28 dp between it and the last card once the page is scrolled to its end.
 */
@Composable
fun navClearance(insets: WindowInsets = DoonaTheme.insets): Dp = navBottom(insets) + navHeight() + DoonaTheme.chrome.navGap

/** The expanded capsule's height: 60 dp, or its glyph over a label that a large font makes taller, inside its pill inset. */
@Composable
fun navHeight(): Dp {
    val chrome = DoonaTheme.chrome
    return max(chrome.navHeight, chrome.navPillInset * 2 + chrome.navGlyph + chrome.navLabelGap + max(chrome.navLabel, lineHeightOf(DoonaTheme.type.navLabel)))
}

/**
 * Whether the capsule is collapsed for [scroll]: from the moment the content moves up (scrolls down) until it moves back
 * down or reaches the top, as Revolut's bar follows the direction of the scroll.
 */
@Composable
fun rememberNavCollapsed(scroll: ScrollState): Boolean {
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
