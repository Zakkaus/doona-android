package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.feature.NOT_WIRED

/**
 * The row of controls at the top of every page, over the content: [content] in a row of 48 dp targets whose 44 dp
 * controls sit [RvChrome.barTop] under the status bar. Behind it a strip of the page fades in by [shade] (0 at rest, 1
 * once the content has moved under it): the content blurred under the page at 85 %, solid to the controls' bottom plus
 * 8 dp, then fading out over 12 dp, so what scrolls under the bar is blurred rather than cut off at a line (S5-24).
 */
@Composable
internal fun RvBar(glass: RvGlass, insets: WindowInsets, shade: () -> Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth()) {
        // The tint fades over the strip's last 12 dp while the blur runs on to its edge, which itself softens over a few dp.
        Box(Modifier.matchParentSize().fadeOutBottom(RvChrome.stripEdge, shade).rvGlass(glass, RectangleShape, Rv.colors.barGlass, RvChrome.stripFade))
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(insets.only(WindowInsetsSides.Top))
                .padding(top = RvChrome.barTop - RvChrome.touchPad, bottom = RvChrome.stripBelow - RvChrome.touchPad + RvChrome.stripFade)
                .height(RvSize.touch),
            contentAlignment = Alignment.CenterStart,
            content = content,
        )
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

/** How far the strip behind a bar has faded in: fully once the content has scrolled [RvChrome.shadeAfter] under it. */
@Composable
internal fun rvShade(scroll: ScrollState): () -> Float {
    val after = with(LocalDensity.current) { RvChrome.shadeAfter.toPx() }
    return { (scroll.value / after).coerceIn(0f, 1f) }
}

/**
 * 活動's top bar (measured §4): the duck mark in a disc, a search pill and two round buttons, 統計 and 訂閱, all 44 dp
 * glass over what scrolls under them, on [RvBar]'s strip. The pill opens [onSearch] and 統計 [onStats]; the duck and
 * 訂閱 open nothing in the sample.
 */
@Composable
internal fun RvTopBar(glass: RvGlass, insets: WindowInsets, shade: () -> Float, onSearch: () -> Unit, onStats: () -> Unit, modifier: Modifier = Modifier) {
    RvBar(glass, insets, shade, modifier) {
        // Each control keeps a 48 dp target round its 44 dp disc, so the gaps between targets are 4 dp narrower.
        Row(Modifier.fillMaxSize().padding(horizontal = RvSpace.gutter - RvChrome.touchPad), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.minimumInteractiveComponentSize().size(RvChrome.control).rvControl(glass, CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_duck), contentDescription = null, Modifier.size(RvChrome.markGlyph))
            }
            Spacer(Modifier.width(RvChrome.controlGap - RvChrome.touchPad))
            SearchPill(glass, onSearch, Modifier.weight(1f))
            Spacer(Modifier.width(RvChrome.controlGap - RvChrome.touchPad))
            GlassButton(glass, R.drawable.rv_ic_analytics, stringResource(R.string.rv_stats), onStats)
            Spacer(Modifier.width(RvChrome.buttonGap - RvChrome.touchPad * 2))
            GlassButton(glass, R.drawable.rv_sym_rss_feed, stringResource(R.string.rv_subscriptions), NOT_WIRED)
        }
    }
}

@Composable
private fun SearchPill(glass: RvGlass, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(RvChrome.control)
            .rvControl(glass, CircleShape)
            .rvPress(CircleShape, Role.Button, onClick)
            .padding(start = RvChrome.searchInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvChrome.searchInset),
    ) {
        RvGlyph(R.drawable.rv_sym_search, RvChrome.searchGlyph, Rv.colors.text)
        RvText(stringResource(R.string.rv_search_nodes), Rv.type.search, color = Rv.colors.textSecondary)
    }
}

@Composable
private fun GlassButton(glass: RvGlass, @DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(RvChrome.control)
            .rvControl(glass, CircleShape)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(icon, RvChrome.controlGlyph, Rv.colors.text) }
}

/** A top bar control's fill: a very soft shadow in light, then the controls' glass. */
@Composable
private fun Modifier.rvControl(glass: RvGlass, shape: Shape): Modifier =
    rvSoftShadow(shape, RvChrome.controlShadow, RvChrome.controlShadowDrop, RvChrome.CONTROL_SHADOW_ALPHA).rvGlass(glass, shape, Rv.colors.controlGlass)

@Composable
internal fun Modifier.rvSoftShadow(shape: Shape, radius: Dp, drop: Dp, alpha: Float): Modifier =
    if (Rv.colors.glassShadow) dropShadow(shape, Shadow(radius, Color.Black, offset = DpOffset(0.dp, drop), alpha = alpha)) else this

/**
 * A pushed page's back control (measured §4, X-04): a 44 dp disc 16 dp from the edge in a 48 dp target, the 24 dp
 * `arrow_back` on white with a faint ring in light, on a raised container in dark.
 */
@Composable
internal fun RvBackDisc(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val back = stringResource(R.string.ui_back)
    val colors = Rv.colors
    val ring = colors.backRing?.let { Modifier.border(RvChrome.ring, it, CircleShape) } ?: Modifier
    Box(
        modifier
            .padding(start = RvSpace.gutter - RvChrome.touchPad)
            .minimumInteractiveComponentSize()
            .size(RvChrome.control)
            .clip(CircleShape)
            .background(colors.backDisc)
            .then(ring)
            .rvPress(CircleShape, Role.Button, onBack)
            .semantics { contentDescription = back },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(R.drawable.rv_sym_arrow_back, RvChrome.controlGlyph, colors.text) }
}
