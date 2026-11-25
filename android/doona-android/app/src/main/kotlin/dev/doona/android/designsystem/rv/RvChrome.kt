package dev.doona.android.designsystem.rv

import android.os.Trace
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import dev.doona.android.R

/**
 * The row of controls at the top of every page, over the content: [content] in a row of 48 dp targets whose 44 dp
 * controls sit [top] under the status bar ([RvChrome.barTop], or [RvChrome.tabBarTop] on a tab home). Behind it a strip of the page fades in by [shade] (0 at rest, 1
 * once the content has moved under it): the content blurred under the page at 85 %, solid to the controls' bottom plus
 * 8 dp, then the blur and its tint fading out together over 12 dp, so what scrolls under the bar is blurred rather than
 * cut off at a line (S5-24). The tint used to fade alone while the blur ran on to the edge, which left a band of
 * untinted blurred content under the row (item 23).
 */
@Composable
internal fun RvBar(
    glass: RvGlass,
    insets: WindowInsets,
    shade: () -> Float,
    modifier: Modifier = Modifier,
    top: Dp = RvChrome.barTop,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().fadeOutBottom(RvChrome.stripFade, shade).rvGlass(glass, RectangleShape, Rv.colors.barGlass))
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(insets.only(WindowInsetsSides.Top))
                .padding(top = top - RvChrome.touchPad, bottom = RvChrome.stripBelow - RvChrome.touchPad + RvChrome.stripFade)
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
}.fadeEdge(fade, atBottom = true)

/**
 * The strip behind a floating bottom action, [RvBar]'s upside down: the page's content blurred under the same glass tint
 * to the bottom edge, fading in over [RvChrome.stripFade] at its top, so what scrolls under the action (a file's code) is
 * blurred, not cut off at a line. It fills [modifier]'s box; the action stands on it.
 */
@Composable
internal fun RvActionStrip(glass: RvGlass, modifier: Modifier = Modifier) {
    Box(modifier.fadeInTop(RvChrome.stripFade).rvGlass(glass, RectangleShape, Rv.colors.barGlass))
}

/**
 * The page's colour from the floating nav's middle down to the screen's edge, fading in over [RvChrome.stripFade] at its
 * top, as [RvActionStrip] does, so no line of what scrolls under the nav shows beside its lower half or under it, where the
 * gesture handle lies. A sample for the owner (owner-fix2 item 8, B), which no page shows unless it asks; it fills
 * [modifier]'s box ([rvNavMiddle] tall), and the nav stands on it.
 */
@Composable
internal fun RvNavFade(modifier: Modifier = Modifier) {
    Box(modifier.fadeInTop(RvChrome.stripFade).background(Rv.colors.page))
}

/** Draws this element with its first [fade] fading in from nothing, so what it covers is not cut off at a line. */
private fun Modifier.fadeInTop(fade: Dp): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.fadeEdge(fade, atBottom = false)

/**
 * Masks this element's last [fade] to nothing ([atBottom]) or its first in from nothing; the element sits in an
 * offscreen layer. The gradient is built once per size, not on every frame the bar or strip redraws.
 */
private fun Modifier.fadeEdge(fade: Dp, atBottom: Boolean): Modifier = drawWithCache {
    val edge = fade.toPx()
    val mask = if (atBottom) {
        Brush.verticalGradient(listOf(Color.Black, Color.Transparent), size.height - edge, size.height)
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, edge)
    }
    onDrawWithContent {
        drawContent()
        drawRect(mask, blendMode = BlendMode.DstIn)
    }
}

/** How far the strip behind a bar has faded in: fully once the content has scrolled [RvChrome.shadeAfter] under it. */
@Composable
internal fun rvShade(scroll: ScrollState): () -> Float {
    val after = with(LocalDensity.current) { RvChrome.shadeAfter.toPx() }
    return { (scroll.value / after).coerceIn(0f, 1f) }
}

/**
 * 活動's top bar (measured §4): [RvTabBarRow] with the search pill and two round buttons, 統計 and 訂閱, all 44 dp
 * glass over what scrolls under them, on [RvBar]'s strip. The pill opens [onSearch], 統計 [onStats] and 訂閱
 * [onSubscriptions].
 */
@Composable
internal fun RvTopBar(
    glass: RvGlass,
    insets: WindowInsets,
    shade: () -> Float,
    onSearch: () -> Unit,
    onStats: () -> Unit,
    modifier: Modifier = Modifier,
    pull: RvPullLook = RvPullLook.None,
    onSubscriptions: () -> Unit,
) {
    RvBar(glass, insets, shade, modifier, RvChrome.tabBarTop) {
        RvTabBarRow(glass, { RvSearchPill(onSearch, it, glass, pull) }) {
            RvGlassButton(glass, R.drawable.rv_ic_analytics, stringResource(R.string.rv_stats), onStats)
            RvGlassButton(glass, R.drawable.rv_sym_rss_feed, stringResource(R.string.rv_open_subscriptions), onSubscriptions)
        }
    }
}

/**
 * The row of every tab home's bar, 活動's first (measured §4; one bar on every tab, the owner's mock of 2026-11-23): the
 * duck mark in a disc, which opens nothing in the sample, then the search capsule ([search], given the modifier that
 * sizes it), then the tab's own round buttons ([actions]). Without buttons the capsule runs to the gutter.
 */
@Composable
internal fun RvTabBarRow(glass: RvGlass, search: @Composable (Modifier) -> Unit, actions: (@Composable () -> Unit)? = null) {
    // Each control keeps a 48 dp target round its 44 dp disc, so the gaps between targets are 4 dp narrower.
    Row(Modifier.fillMaxSize().padding(horizontal = RvSpace.gutter - RvChrome.touchPad), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.minimumInteractiveComponentSize().size(RvChrome.control).rvControl(glass, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_duck), contentDescription = null, Modifier.size(RvChrome.markGlyph))
        }
        Spacer(Modifier.width(RvChrome.controlGap - RvChrome.touchPad))
        search(Modifier.weight(1f))
        if (actions != null) {
            Spacer(Modifier.width(RvChrome.controlGap - RvChrome.touchPad))
            Row(horizontalArrangement = Arrangement.spacedBy(RvChrome.buttonGap - RvChrome.touchPad * 2), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

/**
 * The search pill (measured §4: 44 dp, full round, the 24 dp magnifier 8 dp in, the 17 sp placeholder, [hint], 搜尋節點
 * unless the tab searches another list): on a tab's bar in the controls' [glass] and a 48 dp target; without glass, on
 * the page with the capsule's look at rest, its 44 dp the layout box, as a node list's first row. On the bar it is also the
 * pull-down's anchor ([barPill]).
 */
@Composable
internal fun RvSearchPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glass: RvGlass? = null,
    pull: RvPullLook = RvPullLook.None,
    hint: String = stringResource(R.string.rv_search_nodes),
) {
    val target = if (glass != null) Modifier.barPill(pull).minimumInteractiveComponentSize() else Modifier
    Row(
        modifier
            .then(target)
            .height(RvChrome.control)
            .rvControl(glass, CircleShape)
            .rvPress(CircleShape, Role.Button, onClick)
            .padding(start = RvChrome.searchInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvChrome.searchInset),
    ) {
        RvGlyph(R.drawable.rv_sym_search, RvChrome.searchGlyph, Rv.colors.text)
        RvText(hint, Rv.type.search, color = Rv.colors.textSecondary)
    }
}

/**
 * The bar pill as the pull-down's anchor: it grows by [RvMotion.ANCHOR_GROW] as the pull's progress runs to 1 (the
 * picker it opens has the same pill as its field), offers the pull to TalkBack as an action, and on a tap becomes the
 * search page's field.
 */
@Composable
private fun Modifier.barPill(pull: RvPullLook): Modifier {
    val open = pull.open
    val label = stringResource(R.string.rv_nodes_title)
    return rvSharedSearch()
        .graphicsLayer {
            val grow = 1f + RvMotion.ANCHOR_GROW * pull.progress()
            scaleX = grow
            scaleY = grow
        }
        .semantics {
            if (open == null) return@semantics
            customActions = listOf(
                CustomAccessibilityAction(label) {
                    open()
                    true
                },
            )
        }
}

/**
 * The search pill as a field (the node picker's bar, a list page's bar): the same glass, glyph and type, with [query]
 * typed in it and [hint], the pill's 搜尋節點 unless the list is another, as the placeholder; CJK runs set in Noto as
 * everywhere else. [focus], when given, can put the cursor (and the keyboard) in it. Without [glass], on a page over the
 * list it searches rather than on a bar ([RvSearchSlot]), it keeps the bar capsule's look at rest: one field everywhere.
 * While [query] is not empty a clear button ([RvChrome.control] wide, its target 48 dp) stands at the field's end and empties
 * it. The system takes the first back for the keyboard, so one back must end both: once the keyboard this field raised goes
 * down, the field lets go of the focus and [onClose], when given, ends the page's search; the search key only hides the
 * keyboard and keeps the results.
 */
@Composable
internal fun RvSearchField(
    glass: RvGlass?,
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    hint: String = stringResource(R.string.rv_search_nodes),
    onClose: (() -> Unit)? = null,
) {
    // Its composition, the text field's first one included, is a section of its own in a Perfetto trace.
    Trace.beginSection(TRACE_FIELD)
    val colors = Rv.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val search = remember { SearchKey() }
    RvKeyboardBack(search, onClose)
    Row(
        modifier
            .then(if (glass != null) Modifier.minimumInteractiveComponentSize() else Modifier)
            .height(RvChrome.control)
            .rvControl(glass, CircleShape)
            .padding(start = RvChrome.searchInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvChrome.searchInset),
    ) {
        RvGlyph(R.drawable.rv_sym_search, RvChrome.searchGlyph, colors.text)
        BasicTextField(
            query,
            onQuery,
            Modifier.weight(1f).then(focus?.let { Modifier.focusRequester(it) } ?: Modifier).semantics { contentDescription = hint },
            textStyle = Rv.type.search.copy(color = colors.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    search.kept = true
                    keyboard?.hide()
                },
            ),
            cursorBrush = SolidColor(colors.positive),
            visualTransformation = { TransformedText(scripted(it.text), OffsetMapping.Identity) },
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) RvText(hint, Rv.type.search, color = colors.textSecondary)
                    field()
                }
            },
        )
        // The end's room: the clear button, or what the field's end padding was.
        if (query.isEmpty()) {
            Spacer(Modifier)
        } else {
            val label = stringResource(R.string.ui_clear)
            Box(
                Modifier.size(RvChrome.control).rvPress(CircleShape, Role.Button, onClick = { onQuery("") }).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { RvGlyph(R.drawable.rv_sym_close, RvChrome.searchGlyph, colors.textSecondary) }
        }
    }
    Trace.endSection()
}

/** Whether the search key, not back, is what hides the keyboard: the results stay. */
private class SearchKey {
    var kept = false
}

/**
 * Lets go of the focus and runs [onClose] once the keyboard that has been up goes down, unless the search key
 * ([SearchKey.kept]) hid it: the system's first back ends the keyboard before the page sees it, so this makes that one back
 * end the search as well.
 */
@Composable
private fun RvKeyboardBack(search: SearchKey, onClose: (() -> Unit)?) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val focus = LocalFocusManager.current
    val close = rememberUpdatedState(onClose)
    LaunchedEffect(search) {
        var up = false
        snapshotFlow { ime.getBottom(density) > 0 }.collect { visible ->
            when {
                visible -> {
                    up = true
                    search.kept = false
                }

                up -> {
                    up = false
                    focus.clearFocus()
                    if (!search.kept) close.value?.invoke()
                }
            }
        }
    }
}

/**
 * A top bar's round action (活動's 統計 and 訂閱): a 44 dp glass disc with the soft shadow and, in dark, its rim, in a 48 dp
 * target, with a disc's press ([rvDiscPress]); [label] is for the screen reader.
 */
@Composable
internal fun RvGlassButton(glass: RvGlass, @DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .rvDiscPress(Modifier.size(RvChrome.control).rvControl(glass, CircleShape), onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(icon, RvChrome.controlGlyph, Rv.colors.text) }
}

/**
 * A top bar control's fill: a very soft shadow in light, then the controls' glass, and its rim in dark. Without [glass]
 * (a search field on the page) the glass's tint lies on the page, so the field looks as the bar's capsule does at rest.
 */
@Composable
private fun Modifier.rvControl(glass: RvGlass?, shape: Shape): Modifier {
    val colors = Rv.colors
    val rim = colors.controlRim?.let { Modifier.border(RvChrome.navRim, it, shape) } ?: Modifier
    val shadow = rvSoftShadow(shape, RvChrome.controlShadow, RvChrome.controlShadowDrop, RvChrome.CONTROL_SHADOW_ALPHA)
    val fill = glass?.let { shadow.rvGlass(it, shape, colors.controlGlass) } ?: shadow.background(colors.controlGlass.over.compositeOver(colors.page), shape)
    return fill.then(rim)
}

@Composable
internal fun Modifier.rvSoftShadow(shape: Shape, radius: Dp, drop: Dp, alpha: Float): Modifier =
    if (Rv.colors.glassShadow) dropShadow(shape, Shadow(radius, Color.Black, offset = DpOffset.Zero.copy(y = drop), alpha = alpha)) else this

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

private const val TRACE_FIELD = "RvSearchField"
