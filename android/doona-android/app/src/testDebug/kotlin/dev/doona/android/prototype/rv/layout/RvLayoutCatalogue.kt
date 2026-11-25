package dev.doona.android.prototype.rv.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBanner
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCheckRow
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvMenuPanel
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSecondaryButton
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.prototype.rv.settings.RvSettingsNavRow
import dev.doona.android.prototype.rv.settings.RvSettingsRow
import dev.doona.android.prototype.rv.settings.RvSettingsSwitchRow

// The layout catalogue (docs/LAYOUT-RULES.md, Table 0): every row kind in every case, drawn by the library's own parts.

/** One text set of the catalogue: Chinese (with mixed Latin) or English. */
internal class RvCatalogueWords(
    val one: String,
    val title: String,
    val sub: String,
    val longSub: String,
    val wrapped: String,
    val mixed: String,
    val mixedSub: String,
    val value: String,
    val button: String,
    val section: String,
    val note: String,
)

internal val RV_CATALOGUE_ZH = RvCatalogueWords(
    "中斷連線時通知", "依 App", "只代理 12 個 App", "只有選取的 App 經由代理，其他 App 與區域網路位址直接連線，連線紀錄不會離開這支手機。",
    "永久連線的 VPN 設定與其他需要換行的很長很長的標題文字", "永久連線的 VPN", "Hong Kong 02 IPLC · 48 ms", "48 ms", "更新", "連線", "只有選取的 App 經由代理。",
)

internal val RV_CATALOGUE_EN = RvCatalogueWords(
    "Notify when disconnected", "Per app", "Only 12 apps are proxied", "Only the selected apps use the proxy; other apps and local network addresses connect directly, and the log never leaves this phone.",
    "Always-on VPN settings and other long titles that need to wrap onto a second line", "Always-on VPN 永久連線", "Hong Kong 02 IPLC · 48 ms", "48 ms", "Update", "Connection", "Only the selected apps use the proxy.",
)

@Composable
internal fun RvLayoutCatalogue(w: RvCatalogueWords) {
    Column(Modifier.padding(bottom = RvSpace.gutter)) {
        RvSectionTitle("L1 ${w.section}", first = true)
        RvCard {
            Row1(w.one) { RvChevron() }
            Row1(w.wrapped) { RvChevron() }
            RvRow({ RvFlag("HK", RvSize.rowDisc) }, "Hong Kong 02", null) { RvChevron() }
        }
        RvSectionTitle("L2 ${w.title}")
        RvCard {
            RvRow({ Disc() }, w.title, w.sub) { RvChevron() }
            RvRow({ Disc() }, w.mixed, w.mixedSub, subtitleEnd = { RvText(w.value, Rv.type.rowSub) }) { RvChevron() }
            RvRow({ Disc() }, w.title, w.longSub) { RvChevron() }
            RvRow({ Disc() }, w.title, w.sub, trailing = { RvText(w.value, Rv.type.rowTitle) })
        }
        RvSectionTitle("L3 switch · check")
        RvCard {
            RvSwitchRow(R.drawable.rv_sym_rule, w.one, true, null) {}
            RvSwitchRow(R.drawable.rv_sym_rule, w.title, false, w.longSub) {}
            RvCheckRow({ Disc() }, w.title, w.sub, true, {})
        }
        RvSectionTitle("S1 ${w.section}")
        RvCard {
            RvSettingsRow(w.one, icon = R.drawable.rv_sym_rule, trailing = { RvChevron() })
            RvSettingsRow(w.wrapped, icon = R.drawable.rv_sym_rule, trailing = { RvChevron() })
            RvSettingsNavRow(w.title, R.drawable.rv_sym_rule, {}, subtitle = w.sub)
            RvSettingsNavRow(w.title, R.drawable.rv_sym_rule, {}, subtitle = w.longSub)
            RvSettingsSwitchRow(w.one, R.drawable.rv_sym_rule, true, {})
            RvSettingsSwitchRow(w.title, R.drawable.rv_sym_rule, false, {}, subtitle = w.longSub)
            RvSettingsRow(w.title, icon = R.drawable.rv_sym_rule, subtitle = w.sub, action = { RvSecondaryButton(w.button, {}) })
            RvSettingsRow(w.one, icon = R.drawable.rv_sym_rule, action = { RvSecondaryButton(w.button, {}) })
        }
        // The catalogue's cards run edge to edge (no gutter), so the footnote's cards' edge is its parent's.
        RvFootnote(w.note, inset = RvSpace.none)
        RvSectionTitle("N1 notice")
        RvBanner(RvNotice(RvNoticeKind.Warning, w.title, w.longSub, w.button, {}), Modifier.padding(horizontal = RvSpace.gutter))
        RvSectionTitle("M1 menu")
        RvMenuPanel(listOf(RvMenuItem(w.one, R.drawable.rv_sym_rule) {}, RvMenuItem(w.title, R.drawable.rv_sym_rule) {}), {}, Modifier.padding(horizontal = RvSpace.gutter))
    }
}

@Composable
private fun Row1(title: String, trailing: @Composable () -> Unit) = RvRow({ Disc() }, title, null, trailing = trailing)

@Composable
private fun Disc() = RvIconDisc(R.drawable.rv_sym_rule)
