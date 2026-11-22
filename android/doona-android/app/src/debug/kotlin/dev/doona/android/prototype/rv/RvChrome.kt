package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.max
import dev.doona.android.R
import dev.doona.android.feature.NOT_WIRED

/**
 * 活動's top bar over the content (measured §4): the duck mark in a disc, a search pill and two round buttons, 統計 and
 * 訂閱, all glass over what scrolls under them. Behind them a glass strip of the page's colour fades in by [shade] (0 at
 * rest, 1 once the content has moved under it), so the content is blurred under the bar rather than cut off, as
 * Revolut's is. None of the controls opens anything in the sample.
 */
@Composable
internal fun RvTopBar(glass: RvGlass, insets: WindowInsets, shade: () -> Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().fadeOutBottom(RvChrome.barFade, shade).rvGlass(glass, RectangleShape, Rv.colors.barGlass))
        TopBarRow(glass, insets)
    }
}

/** Draws this element at [alpha], its last [fade] fading to nothing, so what it covers is not cut off at a line. */
private fun Modifier.fadeOutBottom(fade: Dp, alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), size.height - fade.toPx(), size.height), blendMode = BlendMode.DstIn)
}

@Composable
private fun TopBarRow(glass: RvGlass, insets: WindowInsets) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(insets.only(WindowInsetsSides.Top))
            .padding(top = RvChrome.barTop, bottom = RvChrome.barBottom)
            .padding(horizontal = RvSpace.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(RvChrome.mark).rvControl(glass, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_duck), contentDescription = null, Modifier.size(RvChrome.markGlyph))
        }
        Spacer(Modifier.width(RvChrome.controlGap))
        SearchPill(glass, Modifier.weight(1f))
        Spacer(Modifier.width(RvChrome.controlGap))
        GlassButton(glass, R.drawable.rv_ic_analytics, stringResource(R.string.rv_stats))
        Spacer(Modifier.width(RvChrome.buttonGap))
        GlassButton(glass, R.drawable.rv_sym_dns, stringResource(R.string.rv_subscriptions))
    }
}

@Composable
private fun SearchPill(glass: RvGlass, modifier: Modifier) {
    Row(
        modifier
            .height(RvChrome.control)
            .rvControl(glass, CircleShape)
            .rvPress(CircleShape, Role.Button, NOT_WIRED)
            .padding(start = RvChrome.searchInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvChrome.searchInset),
    ) {
        RvGlyph(R.drawable.rv_sym_search, RvChrome.searchGlyph, Rv.colors.text)
        RvText(stringResource(R.string.rv_search_nodes), Rv.type.search, color = Rv.colors.textSecondary)
    }
}

@Composable
private fun GlassButton(glass: RvGlass, @DrawableRes icon: Int, label: String) {
    Box(
        Modifier
            .size(RvChrome.control)
            .rvControl(glass, CircleShape)
            .rvPress(CircleShape, Role.Button, NOT_WIRED)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(icon, RvChrome.controlGlyph, Rv.colors.text) }
}

/** A top bar control's fill: a very soft shadow in light, then the controls' glass. */
@Composable
private fun Modifier.rvControl(glass: RvGlass, shape: Shape): Modifier =
    softShadow(shape, RvChrome.controlShadow, RvChrome.controlShadowDrop, RvChrome.CONTROL_SHADOW_ALPHA).rvGlass(glass, shape, Rv.colors.controlGlass)

@Composable
private fun Modifier.softShadow(shape: Shape, radius: Dp, drop: Dp, alpha: Float): Modifier =
    if (Rv.colors.glassShadow) dropShadow(shape, Shadow(radius, Color.Black, offset = DpOffset(0.dp, drop), alpha = alpha)) else this

/**
 * The floating bottom bar (measured §9): a glass capsule [RvChrome.navInset] in from the sides and [rvNavBottom] above
 * the screen's edge, a filled glyph over a label for each of [items], the [selected] one on an inner pill. [collapsed]
 * shrinks it to icons only, 336 x 52 dp, centred and almost opaque, as Revolut's bar does while the content scrolls up.
 */
@Composable
internal fun RvFloatingNav(
    items: List<RvNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    glass: RvGlass,
    collapsed: Boolean,
    insets: WindowInsets,
    modifier: Modifier = Modifier,
) {
    val shrink by animateFloatAsState(if (collapsed) 1f else 0f, tween(RvChrome.COLLAPSE_MS), label = "nav")
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = rvNavBottom(insets)), contentAlignment = Alignment.BottomCenter) {
        val width = lerp(maxWidth - RvChrome.navInset * 2, RvChrome.navCollapsedWidth, shrink)
        val height = lerp(RvChrome.navHeight, RvChrome.navCollapsedHeight, shrink)
        val tint = Rv.colors.navGlass
        Row(
            Modifier
                .size(width, height)
                .softShadow(CircleShape, RvChrome.navShadow, RvChrome.navShadowDrop, RvChrome.NAV_SHADOW_ALPHA)
                .rvGlass(glass, CircleShape, RvGlassTint(lerp(tint.over, tint.solid, shrink), tint.solid))
                .padding(RvChrome.navPillInset),
        ) {
            items.forEachIndexed { i, item -> NavItem(item, i == selected, shrink, Modifier.weight(1f)) { onSelect(i) } }
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

/** The space the content keeps free at its end, so its last card clears the capsule. */
@Composable
internal fun rvNavClearance(insets: WindowInsets): Dp = rvNavBottom(insets) + RvChrome.navHeight + RvSpace.cardGap

/** How far the top bar's strip has faded in: fully once the content has scrolled [RvChrome.shadeAfter] under it. */
@Composable
internal fun rvShade(scroll: ScrollState): () -> Float {
    val after = with(LocalDensity.current) { RvChrome.shadeAfter.toPx() }
    return { (scroll.value / after).coerceIn(0f, 1f) }
}

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
