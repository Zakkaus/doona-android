package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaElevation
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.icons.R as IconsR

/**
 * The row of controls at the top of every page, over the content: [content] in a row of 48 dp targets whose 44 dp
 * controls sit 12 dp under the status bar. Behind it a strip of the page fades in by [shade] (0 at rest, 1 once the
 * content has moved under it): the content blurred under the page at 85 %, solid to the controls' bottom plus 8 dp, then
 * the blur and its tint fading out together over 12 dp, so what scrolls under the bar is blurred rather than cut off at
 * a line (S5-24). The tint used to fade alone while the blur ran on to the edge, which left a band of untinted blurred
 * content under the row (item 23).
 */
@Composable
fun DoonaBar(glass: GlassBackdrop, shade: () -> Float, modifier: Modifier = Modifier, insets: WindowInsets = DoonaTheme.insets, content: @Composable BoxScope.() -> Unit) {
    val chrome = DoonaTheme.chrome
    Box(modifier.layoutRole(LayoutRole.Chrome).fillMaxWidth()) {
        Box(Modifier.matchParentSize().fadeOutBottom(chrome.stripFade, shade).glass(glass, RectangleShape, DoonaTheme.colors.barGlass))
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(insets.only(WindowInsetsSides.Top))
                .padding(top = chrome.barTop - chrome.touchPad, bottom = chrome.stripBelow - chrome.touchPad + chrome.stripFade)
                .height(DoonaTheme.size.touch),
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

/** How far the strip behind a bar has faded in for [scroll]: fully once the content has scrolled 48 dp under it. */
@Composable
fun rememberShade(scroll: ScrollState): () -> Float {
    val after = with(LocalDensity.current) { DoonaTheme.chrome.shadeAfter.toPx() }
    return { (scroll.value / after).coerceIn(0f, 1f) }
}

/**
 * A tab home's top bar (活動, measured §4): the duck mark in a disc, a search pill reading [searchHint] that opens
 * [onSearch], and a round glass button for each of [actions] (統計 and 訂閱 on 活動), all 44 dp glass over what scrolls
 * under them in 48 dp targets, on [DoonaBar]'s strip: 10 dp from the duck to the pill and the pill to the buttons, 8 dp
 * between buttons, 16 dp from the edges (A01, X-16).
 */
@Composable
fun DoonaHomeTopBar(
    glass: GlassBackdrop,
    shade: () -> Float,
    searchHint: String,
    onSearch: () -> Unit,
    actions: List<DoonaBarAction>,
    modifier: Modifier = Modifier,
    insets: WindowInsets = DoonaTheme.insets,
) {
    val chrome = DoonaTheme.chrome
    DoonaBar(glass, shade, modifier, insets) {
        // Each control keeps a 48 dp target round its 44 dp disc, so the gaps between targets are 4 dp narrower.
        Row(Modifier.fillMaxSize().padding(horizontal = DoonaTheme.spacing.gutter - chrome.touchPad), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.minimumInteractiveComponentSize().size(chrome.control).glassControl(glass, CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_duck), contentDescription = null, Modifier.size(chrome.markGlyph))
            }
            Spacer(Modifier.width(chrome.controlGap - chrome.touchPad))
            SearchPill(glass, searchHint, onSearch, Modifier.weight(1f))
            actions.forEachIndexed { i, action ->
                Spacer(Modifier.width(if (i == 0) chrome.controlGap - chrome.touchPad else chrome.buttonGap - chrome.touchPad * 2))
                GlassButton(glass, action.icon, action.label, action.onClick)
            }
        }
    }
}

@Composable
private fun SearchPill(glass: GlassBackdrop, hint: String, onClick: () -> Unit, modifier: Modifier) {
    val chrome = DoonaTheme.chrome
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(chrome.control)
            .glassControl(glass, CircleShape)
            .press(CircleShape, Role.Button, onClick)
            .padding(start = chrome.searchInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(chrome.searchInset),
    ) {
        DoonaGlyph(IconsR.drawable.sym_search, chrome.searchGlyph, DoonaTheme.colors.text)
        LineText(hint, DoonaTheme.type.search, color = DoonaTheme.colors.textSecondary)
    }
}

@Composable
private fun GlassButton(glass: GlassBackdrop, @DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val chrome = DoonaTheme.chrome
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(chrome.control)
            .glassControl(glass, CircleShape)
            .press(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { DoonaGlyph(icon, chrome.controlGlyph, DoonaTheme.colors.text) }
}

/** A top bar control's fill: a very soft shadow in light, then the controls' glass, and its rim in dark. */
@Composable
private fun Modifier.glassControl(glass: GlassBackdrop, shape: Shape): Modifier {
    val rim = DoonaTheme.colors.controlRim?.let { Modifier.border(DoonaTheme.size.ring, it, shape) } ?: Modifier
    return softShadow(shape, DoonaElevation.control, DoonaElevation.controlDrop, DoonaElevation.CONTROL_ALPHA)
        .glass(glass, shape, DoonaTheme.colors.controlGlass)
        .then(rim)
}

/**
 * A pushed page's back control (measured §4, X-04): a 44 dp disc 16 dp from the edge in a 48 dp target, the 24 dp
 * `arrow_back` on white with a faint ring in light, on a raised container in dark. A screen reader hears "Back".
 */
@Composable
fun DoonaBackDisc(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val back = stringResource(R.string.ui_back)
    val colors = DoonaTheme.colors
    val chrome = DoonaTheme.chrome
    val ring = colors.backRing?.let { Modifier.border(DoonaTheme.size.ring, it, CircleShape) } ?: Modifier
    Box(
        modifier
            .layoutRole(LayoutRole.Chrome)
            .padding(start = DoonaTheme.spacing.gutter - chrome.touchPad)
            .minimumInteractiveComponentSize()
            .size(chrome.control)
            .clip(CircleShape)
            .background(colors.backDisc)
            .then(ring)
            .press(CircleShape, Role.Button, onBack)
            .semantics { contentDescription = back },
        contentAlignment = Alignment.Center,
    ) { DoonaGlyph(IconsR.drawable.sym_arrow_back, chrome.controlGlyph, colors.text) }
}

/**
 * A round action (A03, measured §6): a 48 dp disc with a 24 dp [icon], and its [label] 17 sp semibold 8 dp under it;
 * only the disc lights up when pressed, and a screen reader reads the label as the button's name.
 */
@Composable
fun DoonaRoundAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val size = DoonaTheme.size
    Column(modifier.layoutRole(LayoutRole.Button).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size.actionDisc)
                .clip(CircleShape)
                .background(DoonaTheme.colors.discOnHeader)
                .press(CircleShape, Role.Button, onClick),
            contentAlignment = Alignment.Center,
        ) {
            DoonaGlyph(icon, size.actionGlyph, DoonaTheme.colors.text)
        }
        Spacer(Modifier.height(DoonaTheme.spacing.discToLabel))
        LineText(label, DoonaTheme.type.action, align = TextAlign.Center)
    }
}
