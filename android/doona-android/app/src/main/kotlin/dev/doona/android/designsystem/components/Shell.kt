package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** One destination of the [DoonaHubBar]: its label and the icon of its first page. */
@Immutable
data class DoonaHubTab(val label: String, @DrawableRes val icon: Int)

/** Icon action with a 48 dp touch target and the 25 dp touch icon (§6). */
@Composable
fun DoonaIconAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) = IconAction(icon, label, onClick, modifier, DoonaTheme.spacing.none)

/** The action, its glyph moved [nudge] inside the 48 dp target (negative to the start): for a glyph that must reach the gutter. */
@Composable
private fun IconAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier, nudge: Dp) {
    Box(
        modifier
            .size(DoonaTheme.size.touch)
            .clip(DoonaTheme.shape.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        DoonaIcon(icon, DoonaTheme.colors.text.primary, Modifier.offset(x = nudge))
    }
}

/**
 * Where the content column of a page sits in the room its parent gives it: centred on a window with a bottom bar, and at
 * the start next to the rail on a short one. There the rail has already taken 80 dp, and a column centred in what is left
 * put 133 dp of empty sheet between the rail and the content.
 */
@Composable
private fun columnAlignment(): Alignment = if (DoonaTheme.compactHeight) Alignment.TopStart else Alignment.TopCenter

/** One icon action of the [DoonaTopBar]. */
@Immutable
class DoonaBarAction(@DrawableRes val icon: Int, val label: String, val onClick: () -> Unit)

/**
 * Where an end of the bar puts a glyph whose drawn shape starts [ink] inside its box, so that the shape and not the box
 * lies on the gutter: the space before the touch target, and how far the glyph moves inside the target when the target
 * would otherwise start outside the window. The shape of the back arrow starts 3 dp inside its box and that of the
 * menu's dots 10 dp inside theirs, so boxes on the gutter leave ragged edges.
 */
private class BarEdge(ink: Dp) {
    private val gap = DoonaTheme.spacing.gutter - ink - (DoonaTheme.size.touch - DoonaTheme.size.icon) / 2
    val padding = maxOf(gap, DoonaTheme.spacing.none)
    val nudge = minOf(gap, DoonaTheme.spacing.none)
}

/**
 * Top app bar (§8): 64 high on the base fill, drawn under the status bar. The start holds the duck mark on a hub
 * page and the back arrow on a pushed page; [actions] sit at the end. The first and last glyph sit on the gutter of
 * the content column, which is at most 600 dp wide and placed by [columnAlignment], by their drawn shape (see [BarEdge]).
 */
@Composable
fun DoonaTopBar(onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: List<DoonaBarAction> = emptyList()) {
    val start = BarEdge(DoonaIconInk.start(R.drawable.ic_arrow_back, DoonaTheme.size.icon))
    val end = actions.lastOrNull()?.let { BarEdge(DoonaIconInk.end(it.icon, DoonaTheme.size.icon)) }
    Box(
        modifier
            .fillMaxWidth()
            .background(DoonaTheme.colors.window)
            .windowInsetsPadding(DoonaTheme.insets.only(WindowInsetsSides.Top)),
        contentAlignment = columnAlignment(),
    ) {
        Row(
            Modifier
                .layoutRole(LayoutRole.TopBar)
                .widthIn(max = DoonaTheme.size.contentMax)
                .fillMaxWidth()
                .height(DoonaTheme.size.topBar),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconAction(R.drawable.ic_arrow_back, stringResource(R.string.ui_back), onBack, Modifier.padding(start = start.padding), start.nudge)
            } else {
                // The mark is not a target: its box starts where its shape must, so it needs no nudge.
                Image(
                    painterResource(R.drawable.ic_duck),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.padding(start = DoonaTheme.spacing.gutter - DoonaIconInk.start(R.drawable.ic_duck, DoonaTheme.size.logo)).size(DoonaTheme.size.logo),
                )
            }
            Spacer(Modifier.weight(1f))
            actions.forEachIndexed { i, action ->
                val last = i == actions.lastIndex && end != null
                IconAction(action.icon, action.label, action.onClick, if (last) Modifier.padding(end = end!!.padding) else Modifier, if (last) -end!!.nudge else DoonaTheme.spacing.none)
            }
        }
    }
}

/**
 * Bottom hub bar (§8): at least 64 high plus the gesture bar, base fill, padding 0 × 8, equal items in a column at most
 * 600 dp wide; each a 56 × 32 pill holding the 25 px icon over a 12/16 label, gap 4, with 6 of air above and below. A
 * larger font makes the tabs, and so the bar, taller; the bar never presses its content on its edges. The current pill
 * takes the selected fill and the accent icon.
 */
@Composable
fun DoonaHubBar(tabs: List<DoonaHubTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .background(c.window)
            .windowInsetsPadding(DoonaTheme.insets.only(WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            Modifier
                .layoutRole(LayoutRole.HubBar)
                .widthIn(max = DoonaTheme.size.contentMax)
                .fillMaxWidth()
                .heightIn(min = DoonaTheme.size.bottomBar)
                .padding(horizontal = DoonaTheme.spacing.item)
                .selectableGroup(),
        ) {
            tabs.forEachIndexed { i, tab -> HubTab(tab, i == selected, Modifier.weight(1f)) { onSelect(i) } }
        }
    }
}

/**
 * Hub rail: the hub bar for a window too short to give the page a bar (see [DoonaHubShell]): the same tabs in a column
 * at the start edge, as wide as [DoonaSize.railWidth] and as tall as the window, centred between the bars of the system.
 */
@Composable
fun DoonaHubRail(tabs: List<DoonaHubTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .layoutRole(LayoutRole.HubBar)
            .fillMaxHeight()
            .width(DoonaTheme.size.railWidth)
            .background(DoonaTheme.colors.window)
            .windowInsetsPadding(DoonaTheme.insets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item, Alignment.CenterVertically),
    ) {
        tabs.forEachIndexed { i, tab -> HubTab(tab, i == selected, Modifier.fillMaxWidth()) { onSelect(i) } }
    }
}

/**
 * The frame of every page of the app: [content] over the hub bar, or beside the hub rail in a window shorter than
 * [DoonaSize.compactHeight], where a bar and the top bar would leave a page under a third of the height. The bar takes
 * the gesture bar's inset, so the page above it does not; beside a rail the page takes it, and the end inset of the window.
 */
@Composable
fun DoonaHubShell(tabs: List<DoonaHubTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val insets = DoonaTheme.insets
    if (DoonaTheme.compactHeight) {
        Row(modifier.fillMaxSize().background(DoonaTheme.colors.window)) {
            DoonaHubRail(tabs, selected, onSelect)
            Box(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(insets.only(WindowInsetsSides.End))) { content() }
        }
    } else {
        Column(modifier.fillMaxSize().background(DoonaTheme.colors.window)) {
            Box(Modifier.weight(1f).consumeWindowInsets(insets.only(WindowInsetsSides.Bottom))) { content() }
            DoonaHubBar(tabs, selected, onSelect)
        }
    }
}

@Composable
private fun HubTab(tab: DoonaHubTab, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = DoonaTheme.colors
    Column(
        modifier
            .heightIn(min = DoonaTheme.size.bottomBar)
            .selectable(on, role = Role.Tab, onClick = onClick)
            .padding(vertical = DoonaTheme.spacing.navInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .size(DoonaTheme.size.hubPillWidth, DoonaTheme.size.hubPillHeight)
                .clip(DoonaTheme.shape.pill)
                .then(if (on) Modifier.background(c.selected) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            DoonaIcon(tab.icon, if (on) c.accent else c.icon)
        }
        Text(
            tab.label,
            style = if (on) DoonaTheme.type.navLabelCurrent else DoonaTheme.type.navLabel,
            color = if (on) c.text.primary else c.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The page sheet (§8): surface fill with 16 px top corners under the top bar; padding 20 top and 16 sides; blocks
 * stacked by the caller in a column at most 600 dp wide, centred on wider windows (D20) and at the start beside the
 * rail on a short one (see [columnAlignment]). The title, the tabs and every block scroll together. When nothing below
 * the sheet takes the gesture bar's inset, the sheet's end does.
 */
@Composable
fun DoonaPageSheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .clip(DoonaTheme.shape.sheet)
            .background(DoonaTheme.colors.surface)
            .verticalScroll(rememberScrollState()),
        contentAlignment = columnAlignment(),
    ) {
        Column(
            Modifier
                .layoutRole(LayoutRole.Sheet)
                .widthIn(max = DoonaTheme.size.contentMax)
                .fillMaxWidth()
                .windowInsetsPadding(DoonaTheme.insets.only(WindowInsetsSides.Bottom))
                .padding(
                    start = DoonaTheme.spacing.gutter,
                    end = DoonaTheme.spacing.gutter,
                    top = DoonaTheme.spacing.sheetTop,
                    bottom = DoonaTheme.spacing.sheetBottom,
                ),
            content = content,
        )
    }
}

/**
 * The page sheet for a long list, such as the apps on a device: the same chrome as [DoonaPageSheet] (surface fill, 16 px
 * top corners, the column at most 600 dp wide and placed by [columnAlignment], padding 20 top, 16 sides and below the
 * last row, and the gesture bar's inset at the end), with [content] a lazy list that scrolls in it and composes the rows
 * in view only. [DoonaPageSheet] scrolls its blocks in a vertical scroll, which gives a lazy list unbounded height and
 * throws, so a list of more than a screenful goes here. Put it under a [DoonaTopBar], as [DoonaPage] does the other.
 */
@Composable
fun DoonaLazyPageSheet(modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    val bottomBar = DoonaTheme.insets.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    Box(
        modifier
            .fillMaxSize()
            .clip(DoonaTheme.shape.sheet)
            .background(DoonaTheme.colors.surface),
        contentAlignment = columnAlignment(),
    ) {
        LazyColumn(
            Modifier
                .layoutRole(LayoutRole.Sheet)
                .widthIn(max = DoonaTheme.size.contentMax)
                .fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(
                start = DoonaTheme.spacing.gutter,
                end = DoonaTheme.spacing.gutter,
                top = DoonaTheme.spacing.sheetTop,
                bottom = DoonaTheme.spacing.sheetBottom + bottomBar,
            ),
            content = content,
        )
    }
}

/** Page title `.rp-h1` 28/36/800 at the top of the sheet: the large title of the top app bar. */
@Composable
fun DoonaPageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DoonaTheme.type.pageTitle, color = DoonaTheme.colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.semantics { heading() })
}

/**
 * Tab row: doona's page links (§8) as native tabs. Each tab is 48 high, padding 0 × 12, 14/500; the current one
 * carries a 2 px pill underline in text colour; tabs 4 apart over a 1 px line. Without [onSelect] the tabs only show
 * which page this is: they say so to a screen reader but offer no action.
 */
@Composable
fun DoonaPageTabs(tabs: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: ((Int) -> Unit)? = null) {
    val c = DoonaTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight)) {
            tabs.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .height(DoonaTheme.size.touch)
                        .then(
                            if (onSelect != null) {
                                Modifier.selectable(on, role = Role.Tab, onClick = { onSelect(i) })
                            } else {
                                Modifier.semantics(mergeDescendants = true) {
                                    role = Role.Tab
                                    this.selected = on
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = DoonaTheme.type.label, color = c.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = DoonaTheme.spacing.fillInset))
                    if (on) {
                        Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(DoonaTheme.size.tabUnderline)
                                    .clip(DoonaTheme.shape.pill)
                                    .background(c.text.primary),
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(DoonaTheme.size.border).background(c.line))
    }
}

/**
 * A page: top bar over the sheet; the window's base colour runs under the status bar. The [DoonaHubShell] puts the
 * hub bar below every page, or a rail beside it.
 */
@Composable
fun DoonaPage(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<DoonaBarAction> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(DoonaTheme.colors.window)) {
        DoonaTopBar(onBack, actions = actions)
        DoonaPageSheet(Modifier.weight(1f), content)
    }
}
