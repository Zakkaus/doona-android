package dev.doona.android.prototype.rv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.rvPress
import kotlin.math.roundToInt

/**
 * A scrolling page whose large title collapses into a bar, as Revolut's Appearance page: the title scrolls up under a
 * strip of the page's colour (Revolut blurs it; the sample's minSdk has no blur) while a 20 sp title fades in on the bar.
 * [content] receives a modifier to put on the block that ends with the large title; the bar takes over as that block's
 * bottom edge passes under it. The content starts at the screen's top, so it begins with [RvBarSpacer] or runs up behind
 * the bar. [onBack] adds the back disc, and puts the compact title beside it instead of centring it.
 */
@Composable
internal fun RvScrollPage(
    title: String,
    insets: WindowInsets,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.(titleBlock: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val barBottom = with(density) { insets.getTop(this) + RvSettingsSize.bar.roundToPx() }
    val fade = with(density) { RvSettingsAlpha.COLLAPSE_DP.dp.toPx() }
    // 0 while the large title is clear of the bar, 1 once its bottom edge is under it.
    val collapse = { ((barBottom + fade - titleBottom) / fade).coerceIn(0f, 1f) }
    Box(modifier.fillMaxSize().background(Rv.colors.page)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            content(Modifier.onGloballyPositioned { titleBottom = it.boundsInWindow().bottom.roundToInt() })
        }
        Bar(title, insets, onBack, collapse, Modifier.align(Alignment.TopStart))
    }
}

/** The room the status bar and the bar take at the top of the scrolling content. */
@Composable
internal fun RvBarSpacer(insets: WindowInsets) {
    Column {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(RvSettingsSize.bar))
    }
}

@Composable
private fun Bar(title: String, insets: WindowInsets, onBack: (() -> Unit)?, collapse: () -> Float, modifier: Modifier) {
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = collapse() }.background(Rv.colors.page.copy(alpha = RvSettingsAlpha.STRIP)))
        Column {
            Spacer(Modifier.windowInsetsTopHeight(insets))
            Box(Modifier.fillMaxWidth().height(RvSettingsSize.bar), contentAlignment = Alignment.CenterStart) {
                // The large title is the heading for a screen reader; this one only repeats it.
                RvSettingsText(
                    title,
                    RvSettingsType.compactTitle,
                    Modifier
                        .fillMaxWidth()
                        .padding(start = if (onBack == null) 0.dp else RvSettingsSpace.margin + RvSettingsSize.backDisc + RvSettingsSpace.margin)
                        .graphicsLayer { alpha = collapse() }
                        .clearAndSetSemantics {},
                    align = if (onBack == null) TextAlign.Center else TextAlign.Start,
                )
                onBack?.let { BackDisc(it) }
            }
        }
    }
}

/** Revolut's back button: a 44 dp disc 16 dp from the edge inside a 48 dp touch target. */
@Composable
private fun BackDisc(onBack: () -> Unit) {
    val back = stringResource(R.string.ui_back)
    Box(
        Modifier
            .padding(start = RvSettingsSpace.margin - (RvSize.touch - RvSettingsSize.backDisc) / 2)
            .minimumInteractiveComponentSize()
            .size(RvSettingsSize.backDisc)
            .clip(CircleShape)
            .background(Rv.colors.roundButton)
            .rvPress(CircleShape, Role.Button, onBack)
            .semantics { contentDescription = back },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(R.drawable.rv_ic_back, RvSettingsSize.backGlyph, Rv.colors.text) }
}
