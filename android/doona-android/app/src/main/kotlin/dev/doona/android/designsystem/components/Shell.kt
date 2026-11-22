package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** One destination of the [DoonaHubBar]: its label and the icon of its first page. */
@Immutable
data class DoonaHubTab(val label: String, @DrawableRes val icon: Int)

/** Icon action with a 48 dp touch target and the 25 dp touch icon (§6). */
@Composable
fun DoonaIconAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(DoonaTheme.size.touch)
            .clip(DoonaTheme.shape.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        DoonaIcon(icon, DoonaTheme.colors.text.primary)
    }
}

internal object DoonaTopBarDefaults {
    /**
     * Padding at the ends of the bar. An icon action holds its 25 dp icon centred in a 48 dp target, so the icon reaches
     * the gutter when the target starts this far inside it.
     */
    val edgeInset get() = DoonaTheme.spacing.gutter - (DoonaTheme.size.touch - DoonaTheme.size.icon) / 2
}

/**
 * Top app bar (§8): 64 high on the base fill, drawn under the status bar. The start holds the duck mark on a hub
 * page and the back arrow on a pushed page; icon actions sit at the end. The first and last glyph sit on the gutter of
 * the content column, which is at most 600 dp wide and centred.
 */
@Composable
fun DoonaTopBar(onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(DoonaTheme.colors.window)
            .windowInsetsPadding(DoonaTheme.insets.only(WindowInsetsSides.Top)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            Modifier
                .layoutRole(LayoutRole.TopBar)
                .widthIn(max = DoonaTheme.size.contentMax)
                .fillMaxWidth()
                .height(DoonaTheme.size.topBar)
                .padding(horizontal = DoonaTopBarDefaults.edgeInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                DoonaIconAction(R.drawable.ic_arrow_back, stringResource(R.string.ui_back), onBack)
            } else {
                Box(Modifier.padding(start = (DoonaTheme.size.touch - DoonaTheme.size.icon) / 2)) {
                    Image(painterResource(R.drawable.ic_duck), contentDescription = stringResource(R.string.app_name), modifier = Modifier.size(DoonaTheme.size.logo))
                }
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}

/**
 * Bottom hub bar (§8): 64 high plus the gesture bar, base fill, padding 0 × 8, equal items in a column at most 600 dp wide; each a 56 × 32 pill
 * holding the 25 px icon over a 12/16 label, gap 4. The current pill takes the selected fill and the accent icon.
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

@Composable
private fun HubTab(tab: DoonaHubTab, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = DoonaTheme.colors
    Column(
        modifier
            .heightIn(min = DoonaTheme.size.bottomBar)
            .selectable(on, role = Role.Tab, onClick = onClick),
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
            DoonaIcon(tab.icon, if (on) c.accent else c.text.secondary)
        }
        Text(
            tab.label,
            style = if (on) DoonaTheme.type.navLabelCurrent else DoonaTheme.type.navLabel,
            color = if (on) c.text.primary else c.text.secondary,
            maxLines = 1,
        )
    }
}

/**
 * The page sheet (§8): surface fill with 16 px top corners under the top bar; padding 20 top and 16 sides; blocks
 * stacked by the caller in a column at most 600 dp wide, centred on wider windows (D20).
 */
@Composable
fun DoonaPageSheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .clip(DoonaTheme.shape.sheet)
            .background(DoonaTheme.colors.surface)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .layoutRole(LayoutRole.Sheet)
                .widthIn(max = DoonaTheme.size.contentMax)
                .fillMaxWidth()
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

/** Page title `.rp-h1` 28/36/800 at the top of the sheet: the large title of the top app bar. */
@Composable
fun DoonaPageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DoonaTheme.type.pageTitle, color = DoonaTheme.colors.text.primary, maxLines = 1, modifier = modifier.semantics { heading() })
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
                    Text(label, style = DoonaTheme.type.label, color = c.text.primary, maxLines = 1, modifier = Modifier.padding(horizontal = DoonaTheme.spacing.fillInset))
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
 * A page: top bar over the sheet; the window's base colour runs under the status bar. The app shell puts the
 * [DoonaHubBar] below every page.
 */
@Composable
fun DoonaPage(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(DoonaTheme.colors.window)) {
        DoonaTopBar(onBack, actions = actions)
        DoonaPageSheet(Modifier.weight(1f), content)
    }
}
