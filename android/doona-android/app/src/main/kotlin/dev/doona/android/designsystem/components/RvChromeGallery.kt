package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.icons.R as IconsR

/** The floating nav's destinations on the gallery pages. */
private val navItems = listOf(
    DoonaNavItem(IconsR.drawable.sym_home, "活動"),
    DoonaNavItem(IconsR.drawable.sym_public, "節點"),
    DoonaNavItem(IconsR.drawable.sym_bar_chart, "統計"),
    DoonaNavItem(IconsR.drawable.sym_settings, "設定"),
)

/** A frame on a gallery page drawn as if it were the top of a screen: no status bar above it. */
private val NoInsets = WindowInsets(0, 0, 0, 0)

/**
 * Chrome: the back disc, a pushed page's large title and the bar it collapses to, a tab home's top bar, the floating nav
 * expanded and collapsed, and the round actions. Glass draws its solid fill where it does not blur (the tests).
 */
@Composable
internal fun RvChromeGallery() = GalleryPage("Chrome") {
    val frame = Modifier.fillMaxWidth().clip(DoonaTheme.shape.card).background(DoonaTheme.colors.page)
    val d = DoonaGalleryDefaults
    Section("DoonaBackDisc") { Box(Modifier.fillMaxWidth()) { DoonaBackDisc(onBack = {}) } }
    Section("DoonaScrollPage: large title, then the bar it collapses to") {
        Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.cardGap)) {
            Box(frame.height(d.page)) {
                DoonaScrollPage("節點", onBack = {}, insets = NoInsets) { titleBlock ->
                    DoonaLargeTitle("節點", titleBlock, NoInsets)
                    DoonaGutter { DoonaCard { DoonaNavRow("香港 01", onClick = {}, value = "42 ms") } }
                }
            }
            Box(frame.height(d.page)) {
                DoonaScrollPage("流量詳情", onBack = {}, compact = true, insets = NoInsets) {
                    DoonaBarSpacer(NoInsets)
                    DoonaGutter { DoonaCard { DoonaStatRow("下載", "1.2 GB") } }
                }
            }
        }
    }
    Section("DoonaHomeTopBar") {
        val glass = rememberGlass()
        Box(frame) {
            val actions = listOf(DoonaBarAction(IconsR.drawable.sym_bar_chart, "統計", onClick = {}), DoonaBarAction(IconsR.drawable.sym_link, "訂閱", onClick = {}))
            DoonaHomeTopBar(glass, shade = { 1f }, searchHint = "搜尋節點", onSearch = {}, actions = actions, insets = NoInsets)
        }
    }
    Section("DoonaFloatingNav: expanded, collapsed") {
        val glass = rememberGlass()
        Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.cardGap)) {
            Box(frame.height(d.nav)) { DoonaFloatingNav(navItems, 0, onSelect = {}, glass, collapsed = false, Modifier.align(Alignment.BottomCenter), NoInsets) }
            Box(frame.height(d.nav)) { DoonaFloatingNav(navItems, 2, onSelect = {}, glass, collapsed = true, Modifier.align(Alignment.BottomCenter), NoInsets) }
        }
    }
    Section("DoonaRoundAction") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            DoonaRoundAction(IconsR.drawable.sym_refresh, "測速", onClick = {})
            DoonaRoundAction(IconsR.drawable.sym_add, "新增", onClick = {})
            DoonaRoundAction(IconsR.drawable.sym_share, "分享", onClick = {})
        }
    }
}

/**
 * The page scaffold as a screen: a tab home with its large title over cards, the floating nav over the page, and the
 * room the nav keeps free at the end of the content ([navClearance]), so the last card clears it once scrolled to the end.
 */
@Composable
internal fun RvPageGallery() {
    DoonaScrollPage(
        "設定",
        overlay = { glass -> DoonaFloatingNav(navItems, 3, onSelect = {}, glass, collapsed = false, Modifier.align(Alignment.BottomCenter)) },
    ) { titleBlock ->
        DoonaLargeTitle("設定", titleBlock)
        DoonaGutter {
            DoonaCard {
                DoonaNavRow("主題", onClick = {}, icon = IconsR.drawable.sym_tune, value = "系統")
                DoonaSwitchRow("自動連線", checked = true, onChange = {}, icon = IconsR.drawable.sym_bolt)
            }
            DoonaCard {
                DoonaNavRow("訂閱", onClick = {}, icon = IconsR.drawable.sym_link)
                DoonaNavRow("區域網路", onClick = {}, icon = IconsR.drawable.sym_lan)
                DoonaValueRow("版本", "0.2.0", icon = IconsR.drawable.sym_settings)
            }
        }
        Spacer(Modifier.height(navClearance()))
    }
}
