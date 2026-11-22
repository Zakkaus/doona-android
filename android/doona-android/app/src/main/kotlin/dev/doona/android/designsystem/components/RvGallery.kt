package dev.doona.android.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.icons.R as IconsR

/*
 * The design system's own components (the RV sample's) on gallery pages, grouped by family, every state each one has
 * once: rows and cards here, with the controls; the chrome and the page scaffold in `RvChromeGallery.kt`; the charts and
 * maps in `RvChartsGallery.kt`. The values are fixed samples; times are UTC.
 */

/** Rows and cards: list rows (one and two lines, flag, current node, flag fallback), headers, section titles, settings rows. */
@Composable
internal fun RvRowsGallery() = GalleryPage("Rows and cards") {
    Section("DoonaListRow in a list card: header, flags, delta, see all") { ListCardSample() }
    Section("DoonaSectionTitle, settings rows: value, switch on and off, radio") {
        Column {
            DoonaSectionTitle("一般", onClick = {})
            DoonaCard {
                DoonaNavRow("主題", onClick = {}, icon = IconsR.drawable.sym_tune, value = "系統")
                DoonaValueRow("版本", "0.2.0", icon = IconsR.drawable.sym_settings)
                DoonaSwitchRow("自動連線", checked = true, onChange = {}, icon = IconsR.drawable.sym_bolt)
                DoonaSwitchRow("區域網路", checked = false, onChange = {}, subtitle = "允許存取區域網路")
            }
            DoonaSectionTitle("路由模式")
            DoonaCard {
                DoonaRadioRow("規則", selected = true, onSelect = {})
                DoonaRadioRow("全域", selected = false, onSelect = {})
            }
        }
    }
    Section("DoonaStatRow, padded card") {
        Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.cardGap)) {
            DoonaCard {
                DoonaCardHeader("關鍵數據", tag = "24 小時")
                DoonaStatRow("延遲中位數", "42 ms")
                DoonaStatRow("可用率", "99.8%")
            }
            DoonaCard(contentPadding = PaddingValues(DoonaTheme.spacing.rowPadding)) {
                LineText("一張有內距的卡片：內容與四邊相距 16 dp。", DoonaTheme.type.rowSub, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ListCardSample() {
    val rowDisc = DoonaTheme.size.rowDisc
    DoonaCard {
        DoonaCardHeader("節點", onClick = {}, tag = "12")
        DoonaListRow(
            "香港 01",
            onClick = {},
            leading = { DoonaFlag("hk", rowDisc, current = true) },
            trailing = { DoonaTrailingValue("42 ms") },
        )
        DoonaListRow(
            "東京 02",
            subtitle = "Shadowsocks",
            leading = { DoonaFlag("jp", rowDisc) },
            trailing = { DoonaTrailingValue("88 ms") { DoonaDelta("12 ms", DeltaDirection.Up, Tone.Err) } },
        )
        DoonaListRow(
            "新加坡 03",
            subtitle = "Trojan",
            leading = { DoonaFlag("sg", rowDisc) },
            trailing = { DoonaTrailingValue("51 ms") { DoonaDelta("8 ms", DeltaDirection.Down, Tone.Ok) } },
        )
        DoonaListRow(
            "自建節點",
            subtitle = "沒有地區",
            leading = { DoonaFlag("zz", rowDisc) },
            trailing = { DoonaTrailingValue(NO_VALUE) { DoonaDelta(NO_VALUE, DeltaDirection.None, Tone.Neutral) } },
        )
        DoonaListRow("訂閱", onClick = {}, leading = { DoonaIconDisc(IconsR.drawable.sym_link) }, trailing = { DoonaChevron() })
        DoonaSeeAll("查看全部", onClick = {})
    }
}

/** Controls: the segmented control, switch, radio, filter and range chips, tool discs, tags, status chips, the value pill. */
@Composable
internal fun RvControlsGallery() = GalleryPage("RV controls") {
    val space = DoonaTheme.spacing
    val colors = DoonaTheme.colors
    Section("DoonaSegmented: three parts, two parts") {
        Column(verticalArrangement = Arrangement.spacedBy(space.cardGap)) {
            DoonaSegmented(listOf("全部 3", "手動", "自動"), selected = 0, onSelect = {})
            DoonaSegmented(listOf("規則", "全域"), selected = 1, onSelect = {})
        }
    }
    Section("DoonaSwitch on, off; DoonaRadio on, off") {
        Row(horizontalArrangement = Arrangement.spacedBy(space.cardGap), verticalAlignment = Alignment.CenterVertically) {
            DoonaSwitch(checked = true)
            DoonaSwitch(checked = false)
            DoonaRadio(selected = true)
            DoonaRadio(selected = false)
        }
    }
    Section("DoonaFilterChip, DoonaRangeChip selected and not, DoonaChartTools") {
        Column(verticalArrangement = Arrangement.spacedBy(space.cardGap)) {
            DoonaFilterChip("本月", onClick = {})
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(DoonaTheme.chart.rangeGap)) {
                    listOf("1H", "1D", "1W").forEachIndexed { i, label -> DoonaRangeChip(label, selected = i == 0, onClick = {}) }
                }
                DoonaChartTools(
                    DoonaBarAction(IconsR.drawable.sym_refresh, "重新測速", onClick = {}),
                    DoonaBarAction(IconsR.drawable.sym_share, "分享", onClick = {}),
                )
            }
        }
    }
    Section("DoonaTag plain and tappable, DoonaStatusChip, DoonaValuePill") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(space.tight),
            verticalArrangement = Arrangement.spacedBy(space.tight),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            DoonaTag("v0.2")
            DoonaTag("手動選擇", onClick = {})
            DoonaTag("平均最快", onClick = {})
            DoonaStatusChip(IconsR.drawable.sym_bolt, "良好", colors.good)
            DoonaStatusChip(IconsR.drawable.sym_timer, "偏慢", colors.slow)
            DoonaValuePill("中位數 42 ms")
        }
    }
}

/** The gallery pages' frames: a scaffold or a map shown in a box of a page's height, the nav in its room above the edge. */
internal object DoonaGalleryDefaults {
    val page = 220.dp
    val nav = 120.dp
    val map = 260.dp
    val usage = 140.dp
}
