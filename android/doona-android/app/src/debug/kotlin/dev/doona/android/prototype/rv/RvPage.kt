package dev.doona.android.prototype.rv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt

/**
 * The one scaffold of every page but 活動: the page scrolls under [RvBar], whose strip blurs it once it moves under the
 * bar. A large-title page (設定, 統計, 節點, 主題) puts [content]'s `titleBlock` modifier on the block that ends with its
 * [RvLargeTitle]; as that block's bottom edge passes under the bar, the 20 sp [title] fades in on the bar. A [compact]
 * page (流量詳情, a node) has only the bar's title, from the start. [onBack] adds the back disc and puts the title beside
 * it; without it (a tab home) the title is centred. The content starts at the screen's top, so it begins with
 * [RvBarSpacer]. [overlay] draws over the page with its [RvGlass] (the floating nav, a page's floating actions).
 */
@Composable
internal fun RvScrollPage(
    title: String,
    insets: WindowInsets,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    compact: Boolean = false,
    scroll: ScrollState = rememberScrollState(),
    overlay: @Composable BoxScope.(RvGlass) -> Unit = {},
    content: @Composable ColumnScope.(titleBlock: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val barBottom = with(density) { insets.getTop(this) + rvBarHeight().roundToPx() }
    val fade = with(density) { RvChrome.collapse.toPx() }
    // 0 while the large title is clear of the bar, 1 once its bottom edge is under it.
    val collapse = if (compact) ({ 1f }) else ({ ((barBottom + fade - titleBottom) / fade).coerceIn(0f, 1f) })
    val glass = rememberRvGlass()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().rvGlassSource(glass).background(Rv.colors.page).verticalScroll(scroll)) {
            content(Modifier.onGloballyPositioned { titleBottom = it.boundsInWindow().bottom.roundToInt() })
        }
        RvBar(glass, insets, rvShade(scroll), Modifier.align(Alignment.TopStart)) {
            // A large title is the heading for a screen reader, and the bar's only repeats it.
            val semantics = if (compact) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {}
            RvText(
                title,
                Rv.type.barTitle,
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (onBack == null) RvChrome.titleStart - RvSpace.gutter else RvChrome.titleStart, end = RvChrome.titleStart - RvSpace.gutter)
                    .graphicsLayer { alpha = collapse() }
                    .then(semantics),
                align = if (onBack == null) TextAlign.Center else TextAlign.Start,
            )
            onBack?.let { RvBackDisc(it) }
        }
        overlay(glass)
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

/** A pushed page's large title, 32 sp bold under the bar; [titleBlock] is the modifier [RvScrollPage] hands its content. */
@Composable
internal fun RvLargeTitle(text: String, titleBlock: Modifier, insets: WindowInsets) {
    RvBarSpacer(insets)
    Spacer(Modifier.height(RvChrome.largeTitleTop))
    RvText(text, Rv.type.largeTitle, titleBlock.padding(horizontal = RvSpace.gutter).semantics { heading() })
    Spacer(Modifier.height(RvChrome.largeTitleBottom))
}
