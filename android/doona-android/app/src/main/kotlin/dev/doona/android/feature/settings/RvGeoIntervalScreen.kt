package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvRadioRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvSettingsRadioCard
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.feature.model.RvGeoInterval

/**
 * 自動更新 for the GeoIP/GeoSite data, a page of 設定's 更新 group (owner, 2026-11-26 07:13, q-p6a-geointerval), laid out
 * as 日誌等級: the large title, one card of the three intervals as radio rows, 每天 over 預設
 * (honk's own 24 hours; owner, 2026-11-26, q-k4-geodefault), and one footer line: the
 * data updates only while the VPN is up, through the main node. [selected] is honk's interval; a choice goes to
 * [onSelect]. Null, honk is not running (VPN off): the rows are drawn disabled with none chosen, and the footer reads
 * 未連線 (owner, 2026-11-26, q-k4-geooffline).
 */
@Composable
internal fun RvGeoIntervalScreen(selected: RvGeoInterval?, onSelect: (RvGeoInterval) -> Unit, onBack: () -> Unit, banner: RvNotice? = null) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_auto_update)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvBannerSlot(banner)
        RvSettingsColumn {
            RvSettingsRadioCard {
                RvGeoInterval.entries.forEach {
                    val default = if (it == RvGeoInterval.Daily) stringResource(R.string.rv_geo_interval_default) else null
                    RvRadioRow(null, stringResource(it.label), it == selected, default, enabled = selected != null, onSelect = { onSelect(it) })
                }
            }
            RvFootnote(stringResource(if (selected == null) R.string.status_stopped else R.string.rv_geo_interval_footer))
        }
    }
}
