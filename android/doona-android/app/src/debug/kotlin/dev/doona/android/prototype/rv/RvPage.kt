package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt

/**
 * The one scaffold of every page but 活動: the page scrolls under [RvBar], whose strip blurs it once it moves under the
 * bar. A large-title page (設定, 統計, 節點, 主題) puts [content]'s `titleBlock` modifier on the block that ends with its
 * [RvLargeTitle]; as that block's bottom edge passes under the bar, the 20 sp [title] fades in on the bar. [onBack] adds
 * the back disc and puts the title beside it; without it (a tab home) the title is centred. The content starts at the screen's top, so it begins with
 * [RvBarSpacer]. [actions] puts round glass buttons ([RvGlassButton]) at the bar's end, on its row and in 48 dp
 * targets, as 活動's top bar has them. [bottomAction] floats a page's one action (an [RvPrimaryButton]) on the gutter,
 * 16 dp above the inset, with no scrim; the page keeps its room under the content, so the last card scrolls clear of
 * it. [overlay] draws over the page with its [RvGlass] (the floating nav, a page's floating actions). [field] puts a list
 * page's search field ([RvSearchField]) on the bar beside the back disc, in the title's place, as the node picker's bar
 * holds it; it draws in the bar's glass with the modifier that sizes it to the row.
 */
@Composable
internal fun RvScrollPage(
    title: String,
    insets: WindowInsets,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    scroll: ScrollState = rememberScrollState(),
    actions: (@Composable (RvGlass) -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    overlay: @Composable BoxScope.(RvGlass) -> Unit = {},
    field: (@Composable (RvGlass, Modifier) -> Unit)? = null,
    content: @Composable ColumnScope.(titleBlock: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val barBottom = with(density) { insets.getTop(this) + rvBarHeight().roundToPx() }
    val fade = with(density) { RvChrome.collapse.toPx() }
    // 0 while the large title is clear of the bar, 1 once its bottom edge is under it.
    val collapse = { ((barBottom + fade - titleBottom) / fade).coerceIn(0f, 1f) }
    val glass = rememberRvGlass()
    var actionHeight by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().rvGlassSource(glass).background(Rv.colors.page).verticalScroll(scroll)) {
            content(Modifier.onGloballyPositioned { titleBottom = it.boundsInWindow().bottom.roundToInt() })
            // The bottom action's room: the gap above it, then the action with its 16 dp over the inset.
            if (bottomAction != null) Spacer(Modifier.height(RvSpace.gutter + with(density) { actionHeight.toDp() }))
        }
        RvBar(glass, insets, rvShade(scroll), Modifier.align(Alignment.TopStart)) {
            if (field != null) FieldBar(glass, onBack, field) else TitleBar(title, onBack, collapse)
        }
        actions?.let {
            // Each disc keeps its 48 dp target, so the gap between targets is the gap between discs less the overhangs.
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Top))
                    .padding(top = RvChrome.barTop - RvChrome.touchPad, end = RvSpace.gutter - RvChrome.touchPad),
                horizontalArrangement = Arrangement.spacedBy(RvChrome.buttonGap - RvChrome.touchPad * 2),
            ) { it(glass) }
        }
        bottomAction?.let {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { size -> actionHeight = size.height }.padding(horizontal = RvSpace.gutter)) {
                it()
                Spacer(Modifier.height(RvSpace.gutter))
                Spacer(Modifier.windowInsetsBottomHeight(insets))
            }
        }
        overlay(glass)
    }
}

/** The bar's title, faded in by [collapse], beside the back disc or centred without one. */
@Composable
private fun TitleBar(title: String, onBack: (() -> Unit)?, collapse: () -> Float) {
    // The page's large title is the heading for a screen reader, and the bar's only repeats it.
    RvText(
        title,
        Rv.type.barTitle,
        Modifier
            .fillMaxWidth()
            .padding(start = if (onBack == null) RvChrome.titleStart - RvSpace.gutter else RvChrome.titleStart, end = RvChrome.titleStart - RvSpace.gutter)
            .graphicsLayer { alpha = collapse() }
            .clearAndSetSemantics {},
        align = if (onBack == null) TextAlign.Center else TextAlign.Start,
    )
    onBack?.let { RvBackDisc(it) }
}

/** The bar of a page with a search [field]: the back disc, then the field to the bar's end, as the node picker's bar. */
@Composable
private fun FieldBar(glass: RvGlass, onBack: (() -> Unit)?, field: @Composable (RvGlass, Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            RvBackDisc(onBack)
            Spacer(Modifier.width(RvChrome.controlGap - RvChrome.touchPad))
        } else {
            Spacer(Modifier.width(RvSpace.gutter - RvChrome.touchPad))
        }
        field(glass, Modifier.weight(1f).padding(end = RvSpace.gutter - RvChrome.touchPad))
    }
}

/** From the screen's top to the bar's controls' bottom, under the status bar: what the content keeps free above it. */
internal fun rvBarHeight() = RvChrome.barTop + RvChrome.control

/** The room the status bar and the bar's controls take at the top of the scrolling content. */
@Composable
internal fun RvBarSpacer(insets: WindowInsets) {
    Column {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(rvBarHeight()))
    }
}

/**
 * The head of every large-title page, one layout for all of them (spacing audit 2026-11-23, measured §2, §4): the bar's
 * room, then [RvChrome.largeTitleTop] under the bar's controls an optional [mark] (a node's flag) [RvSpace.markToTitle]
 * over the 32 sp title on the gutter, then [caption] (the page's description, [RvCaption] lines) [RvSpace.captionGap] under
 * the title's line box. An optional [control] row on the page (filter chips, a status chip) sits [RvSpace.controlGap] under
 * that block and [RvSpace.controlGap] over what follows, as Revolut's Analytics sets its chips; without one, the first card
 * or section title follows [RvSpace.headerGap] under the block and adds nothing above itself ([RvSectionTitle]'s `first`).
 * [titleBlock] is the modifier [RvScrollPage] hands its content.
 */
@Composable
internal fun RvPageHeader(
    title: String,
    titleBlock: Modifier,
    insets: WindowInsets,
    mark: (@Composable () -> Unit)? = null,
    caption: (@Composable ColumnScope.() -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
) {
    RvBarSpacer(insets)
    Spacer(Modifier.height(RvChrome.largeTitleTop))
    mark?.let {
        Box(Modifier.padding(start = RvSpace.gutter, bottom = RvSpace.markToTitle).testTag(RvPart.MARK)) { it() }
    }
    RvText(text = title, style = Rv.type.largeTitle, modifier = titleBlock.padding(horizontal = RvSpace.gutter).testTag(RvPart.TITLE).semantics { heading() })
    caption?.let {
        Column(
            Modifier.padding(start = RvSpace.gutter, top = RvSpace.captionGap, end = RvSpace.gutter).testTag(RvPart.CAPTION),
            verticalArrangement = Arrangement.spacedBy(RvSpace.tight),
            content = it,
        )
    }
    if (control != null) RvControlRow(control) else Spacer(Modifier.height(RvSpace.headerGap))
}

/**
 * A control row on the page (filter chips, a status chip, a list's search and order), on the gutter with
 * [RvSpace.controlGap] above and below it, as Revolut's Analytics sets its chips between its title and its first card.
 */
@Composable
internal fun RvControlRow(control: @Composable () -> Unit) {
    Spacer(Modifier.height(RvSpace.controlGap))
    Box(Modifier.padding(horizontal = RvSpace.gutter).testTag(RvPart.CONTROL)) { control() }
    Spacer(Modifier.height(RvSpace.controlGap))
}

/** A pushed page's large title alone: [RvPageHeader] with no caption and no control. */
@Composable
internal fun RvLargeTitle(text: String, titleBlock: Modifier, insets: WindowInsets) = RvPageHeader(text, titleBlock, insets)

/** One line of a page header's caption, in secondary text, after an optional 16 dp [icon] in the same colour. */
@Composable
internal fun RvCaption(text: String, @DrawableRes icon: Int? = null) {
    val color = Rv.colors.textSecondary
    if (icon == null) RvText(text, Rv.type.rowSub, color = color) else RvInline(icon, text, color, Rv.type.rowSub)
}
