package dev.doona.android.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.designsystem.theme.DoonaTheme
import kotlin.math.roundToInt

/**
 * The one scaffold of a pushed page and of a tab home with a large title (設定, 統計, 節點, 主題, 流量詳情, a node),
 * edge to edge: the page scrolls from the screen's top under [DoonaBar], whose strip blurs it once it moves under the
 * bar. A large-title page puts [content]'s `titleBlock` modifier on the block that ends with its [DoonaLargeTitle]; as
 * that block's bottom edge passes under the bar, the 20 sp [title] fades in on the bar. A [compact] page has only the
 * bar's title, from the start. [onBack] adds the back disc and puts the title beside it; without it (a tab home) the
 * title is centred. The content begins with [DoonaBarSpacer] (or the large title, which has it). [overlay] draws over
 * the page with its [GlassBackdrop]: the floating nav, a page's floating actions. [insets] are the status bar's and
 * the gesture bar's ([DoonaTheme.insets]: the window's, or a test frame's).
 */
@Composable
fun DoonaScrollPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    compact: Boolean = false,
    scroll: ScrollState = rememberScrollState(),
    insets: WindowInsets = DoonaTheme.insets,
    overlay: @Composable BoxScope.(GlassBackdrop) -> Unit = {},
    content: @Composable ColumnScope.(titleBlock: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val chrome = DoonaTheme.chrome
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val barBottom = with(density) { insets.getTop(this) + barHeight().roundToPx() }
    val fade = with(density) { chrome.collapse.toPx() }
    // 0 while the large title is clear of the bar, 1 once its bottom edge is under it.
    val collapse = if (compact) ({ 1f }) else ({ ((barBottom + fade - titleBottom) / fade).coerceIn(0f, 1f) })
    // Read in composition, so the bar title's semantics follow it; it changes only when the title is handed over.
    val shown by remember(compact, barBottom, fade) { derivedStateOf { collapse() >= 1f } }
    val handedOver = shown
    val glass = rememberGlass()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().glassSource(glass).background(DoonaTheme.colors.page).verticalScroll(scroll)) {
            content(Modifier.onGloballyPositioned { titleBottom = it.boundsInWindow().bottom.roundToInt() })
        }
        DoonaBar(glass, rememberShade(scroll), Modifier.align(Alignment.TopStart), insets) {
            // A large title is the heading for a screen reader, and the bar's only repeats it.
            val semantics = if (compact) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics { this[BarTitleShownKey] = handedOver }
            LineText(
                title,
                DoonaTheme.type.barTitle,
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (onBack == null) chrome.titleStart - DoonaTheme.spacing.gutter else chrome.titleStart, end = chrome.titleStart - DoonaTheme.spacing.gutter)
                    .graphicsLayer { alpha = collapse() }
                    .then(semantics),
                align = if (onBack == null) TextAlign.Center else TextAlign.Start,
            )
            onBack?.let { DoonaBackDisc(it) }
        }
        overlay(glass)
    }
}

/** Whether the bar's title of a large-title page shows: the large title has scrolled under the bar (for tests). */
internal val BarTitleShownKey = SemanticsPropertyKey<Boolean>("DoonaBarTitleShown")

/** From the screen's top to the bar's controls' bottom, under the status bar: what the content keeps free above it. */
fun barHeight() = DoonaTheme.chrome.barTop + DoonaTheme.chrome.control

/** The room the status bar and the bar's controls take at the top of a scrolling page's content. */
@Composable
fun DoonaBarSpacer(insets: WindowInsets = DoonaTheme.insets) {
    Column {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(barHeight()))
    }
}

/** A page's large title, 32 sp bold under the bar (S8-03); [titleBlock] is the modifier [DoonaScrollPage] hands its content. */
@Composable
fun DoonaLargeTitle(text: String, titleBlock: Modifier, insets: WindowInsets = DoonaTheme.insets) {
    val chrome = DoonaTheme.chrome
    DoonaBarSpacer(insets)
    Spacer(Modifier.height(chrome.largeTitleTop))
    LineText(text, DoonaTheme.type.largeTitle, titleBlock.layoutRole(LayoutRole.Heading).padding(horizontal = DoonaTheme.spacing.gutter).semantics { heading() })
    Spacer(Modifier.height(chrome.largeTitleBottom))
}

/** A page's column of cards: 16 dp gutters, the cards 16 dp apart (X-03). */
@Composable
fun DoonaGutter(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = DoonaTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.cardGap),
        content = content,
    )
}
