package dev.doona.android.designsystem.rv

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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * The one scaffold of every page but 活動: the page scrolls under [RvBar], whose strip blurs it once it moves under the
 * bar. A pushed page ([onBack] adds the back disc) puts [content]'s `titleBlock` modifier on the block that ends with
 * its [RvLargeTitle]; as that block's bottom edge passes under the bar, the 20 sp [title] fades in on the bar beside the
 * disc. A page with a [field] and no [onBack] is a tab home (策略, 連線): 活動's bar ([RvTabBarRow]) at its height
 * ([RvChrome.tabBarTop]), [field] as its search capsule and [actions] after it, and no large title; its content begins
 * with [RvTabHeader]. A page with neither (歡迎, 設定's hero) centres the title on the bar, fading in as the
 * `titleBlock` passes under it. The content starts at the screen's top, so it begins with [RvBarSpacer]. On any other
 * page, [actions] puts round glass buttons
 * ([RvGlassButton]) at the bar's end, on its row and in 48 dp targets, and [field] puts a list page's search field
 * ([RvSearchField]) beside the back disc, in the title's place, as the node picker's bar holds it; either draws in the
 * bar's glass, [field] with the modifier that sizes it to the row. [bottomAction] floats a page's one action (an
 * [RvPrimaryButton]) on the gutter, 16 dp above the inset, or above the keyboard while it is up, over [RvActionStrip] ([actionScrim],
 * on for every page, device audit 2026-11-25 finding 13): what scrolls under the action is blurred there, not showing through around
 * the pill; the page keeps its room under the content, so the last card scrolls clear of it. [overlay] draws over the page with its [RvGlass] (the floating nav, a page's
 * floating actions). Without a [bottomAction] the content ends with [end]'s room ([RvPageEndRoom]): the navigation bar's
 * inset (or the keyboard's, while it is up) under a gap, or the floating nav's clearance on a tab home.
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
    actionScrim: Boolean = false,
    overlay: @Composable BoxScope.(RvGlass) -> Unit = {},
    field: (@Composable (RvGlass, Modifier) -> Unit)? = null,
    end: RvPageEnd = RvPageEnd.Inset(),
    content: @Composable ColumnScope.(titleBlock: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val tab = field != null && onBack == null
    val barTop = if (tab) RvChrome.tabBarTop else RvChrome.barTop
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val barBottom = with(density) { insets.getTop(this) + rvBarHeight(barTop).roundToPx() }
    val fade = with(density) { RvChrome.collapse.toPx() }
    // 0 while the large title is clear of the bar, 1 once its bottom edge is under it.
    val collapse = { ((barBottom + fade - titleBottom) / fade).coerceIn(0f, 1f) }
    val glass = rememberRvGlass()
    // What stands at the window's foot: the navigation bar, or the keyboard once it rises, which a bottom action and a pushed page's end follow.
    val foot = insets.union(WindowInsets.ime)
    var actionHeight by remember { mutableIntStateOf(0) }
    // The page's foot in root px, and from it where the content stops being in the clear above the bottom action.
    var bottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val gap = with(density) { RvSpace.gutter.toPx() }
    val floor = remember(gap) { { bottom - gap - actionHeight } }
    Box(modifier.fillMaxSize().onGloballyPositioned { bottom = it.positionInRoot().y + it.size.height }) {
        Column(Modifier.fillMaxSize().rvGlassSource(glass).background(Rv.colors.page).verticalScroll(scroll)) {
            CompositionLocalProvider(LocalRvContentFloor provides floor.takeIf { bottomAction != null }) {
                content(Modifier.onGloballyPositioned { titleBottom = it.boundsInWindow().bottom.roundToInt() })
            }
            // The bottom action's room: the gap above it, then the action with its 16 dp over the inset.
            if (bottomAction != null) Spacer(Modifier.height(RvSpace.gutter + with(density) { actionHeight.toDp() })) else RvPageEndRoom(end, if (end is RvPageEnd.Inset) foot else insets)
        }
        RvBar(glass, insets, rvShade(scroll), Modifier.align(Alignment.TopStart), barTop) {
            when {
                field == null -> TitleBar(title, onBack, collapse)
                onBack == null -> RvTabBarRow(glass, { field(glass, it) }, actions?.let { { it(glass) } })
                else -> FieldBar(glass, onBack, field)
            }
        }
        // A tab home's buttons are on its bar's row.
        if (!tab) {
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
        }
        bottomAction?.let {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                if (actionScrim) RvActionStrip(glass, Modifier.matchParentSize())
                // The strip's fade lies in the gap above the action, which the content's room already leaves.
                val fade = if (actionScrim) Modifier.padding(top = RvChrome.stripFade) else Modifier
                Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter).then(fade).onSizeChanged { size -> actionHeight = size.height }) {
                    it()
                    Spacer(Modifier.height(RvSpace.gutter))
                    Spacer(Modifier.windowInsetsBottomHeight(foot))
                }
            }
        }
        overlay(glass)
    }
}

/**
 * What a page keeps free under its last content, the one end rule of every page, so that scrolled to its end the last
 * line ends above whatever stands at the window's foot: [Inset] on a pushed page, [Nav] on a tab home. A page with a
 * bottom action keeps the action's room instead ([RvScrollPage]).
 */
internal sealed interface RvPageEnd {
    /** [gap] under the last card, then the navigation bar's inset (the gesture handle's strip or three-button bar). */
    class Inset(val gap: Dp = RvSpace.cardGap) : RvPageEnd

    /** The floating nav's clearance ([rvNavClearance]): over the inset, the capsule, then [RvChrome.navGap]. */
    data object Nav : RvPageEnd
}

/** [end]'s room at the foot of a page's content, read from [insets]. */
@Composable
internal fun RvPageEndRoom(end: RvPageEnd, insets: WindowInsets) {
    when (end) {
        is RvPageEnd.Inset -> {
            Spacer(Modifier.height(end.gap))
            Spacer(Modifier.windowInsetsBottomHeight(insets))
        }

        RvPageEnd.Nav -> Spacer(Modifier.height(rvNavClearance(insets)))
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
private fun FieldBar(glass: RvGlass, onBack: () -> Unit, field: @Composable (RvGlass, Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RvBackDisc(onBack)
        Spacer(Modifier.width(RvChrome.controlGap - RvChrome.touchPad))
        field(glass, Modifier.weight(1f).padding(end = RvSpace.gutter - RvChrome.touchPad))
    }
}

/**
 * From the status bar to the bar's controls' bottom, for a bar whose controls sit [top] under it: what the content keeps
 * free above it.
 */
internal fun rvBarHeight(top: Dp = RvChrome.barTop) = top + RvChrome.control

/** The room the status bar and the bar's controls take at the top of the scrolling content, for a bar at [top]. */
@Composable
internal fun RvBarSpacer(insets: WindowInsets, top: Dp = RvChrome.barTop) {
    Column {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(rvBarHeight(top)))
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
 * [RvSpace.controlGap] above and below it wherever it stands, under a page's title or between two cards (策略 全域's
 * order between its mode card and its node list), as Revolut's Analytics sets its chips between its title and its
 * first card: one value (the owner, 2026-11-25).
 */
@Composable
internal fun RvControlRow(control: @Composable () -> Unit) {
    Spacer(Modifier.height(RvSpace.controlGap))
    Box(Modifier.padding(horizontal = RvSpace.gutter).testTag(RvPart.CONTROL)) { control() }
    Spacer(Modifier.height(RvSpace.controlGap))
}

/**
 * The head of a tab home (策略, 連線), which has no large title, as Revolut's tabs have none (revolut-ux3 §5): the
 * bar's room ([RvChrome.tabBarTop]), then [RvSpace.searchGap] from its search capsule to the first content. An optional
 * [caption] ([RvCaption] lines) and [control] row follow, and the first card after them, each [RvSpace.tabBlockGap] under
 * the one above.
 */
@Composable
internal fun RvTabHeader(insets: WindowInsets, caption: (@Composable ColumnScope.() -> Unit)? = null, control: (@Composable () -> Unit)? = null) {
    RvBarSpacer(insets, RvChrome.tabBarTop)
    Spacer(Modifier.height(RvSpace.searchGap))
    caption?.let {
        Column(Modifier.padding(horizontal = RvSpace.gutter).testTag(RvPart.CAPTION), verticalArrangement = Arrangement.spacedBy(RvSpace.tight), content = it)
    }
    control?.let {
        if (caption != null) Spacer(Modifier.height(RvSpace.tabBlockGap))
        Box(Modifier.padding(horizontal = RvSpace.gutter).testTag(RvPart.CONTROL)) { it() }
    }
    if (caption != null || control != null) Spacer(Modifier.height(RvSpace.tabBlockGap))
}

/**
 * An in-page search field ([field], given the modifier that sizes and tags it) over the list it searches: a control of
 * the page, [RvSpace.controlGap] under what is above it, and the list [RvSpace.searchGap] under it, as a tab's content
 * sits under its bar's capsule.
 */
@Composable
internal fun RvSearchSlot(field: @Composable (Modifier) -> Unit) {
    Spacer(Modifier.height(RvSpace.controlGap))
    field(Modifier.fillMaxWidth().testTag(RvPart.SEARCH))
    Spacer(Modifier.height(RvSpace.searchGap))
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
