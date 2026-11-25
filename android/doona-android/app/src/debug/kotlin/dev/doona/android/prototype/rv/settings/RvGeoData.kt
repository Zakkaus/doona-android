package dev.doona.android.prototype.rv.settings

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvSecondaryButton
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.prototype.rv.config.rvConfigLine

/**
 * 設定's GeoIP/GeoSite row in 進階: the data's date as its second line, with where an update stands after it, and 更新
 * at its end, the library's in-card action sized to its label and centred on the row (as every in-row action,
 * [RvSettingsRow]). While it runs, the busy bar takes the action's place, as
 * 關於's 檢查更新 row while it checks. A failure stays on the row (M8: the page shows it in place, no dialog or toast):
 * 無法更新 in the error hue, and the action says 重試. Before the VPN is up, the action is the library's disabled pill
 * and the line says 未連線, so the update waits for the connection.
 */
@Composable
internal fun RvGeoDataRow(data: RvGeoData, onUpdate: () -> Unit) {
    val failed = data.update == RvGeoUpdate.Failed
    val date = rememberRvFormat().date(data.dateEpochMs)
    // Each part whole, so a line that wraps at a large font size breaks after the dot, never inside 已是最新.
    val line = rvConfigLine(listOfNotNull(date, geoState(data)?.let { stringResource(it) }))
    val action: @Composable () -> Unit = {
        if (data.update == RvGeoUpdate.Updating) {
            RvBusy(Modifier.width(RvSize.busy))
        } else {
            val label = stringResource(if (failed) R.string.rv_retry else R.string.rv_menu_update)
            // As wide as its label rather than the row.
            RvSecondaryButton(label, onUpdate, Modifier.width(IntrinsicSize.Max), enabled = data.connected)
        }
    }
    RvSettingsRow(stringResource(R.string.rv_settings_geodata), icon = R.drawable.rv_sym_directions, subtitle = line, error = failed, action = action)
}

/** What follows the date on the row's second line: the update's outcome, else 未連線 while the update waits, else nothing. */
private fun geoState(data: RvGeoData): Int? = when (data.update) {
    RvGeoUpdate.Updating -> R.string.rv_geodata_updating
    RvGeoUpdate.Failed -> R.string.rv_geodata_failed
    RvGeoUpdate.Updated -> R.string.rv_geodata_updated.takeIf { data.connected }
    RvGeoUpdate.Latest -> R.string.rv_update_latest.takeIf { data.connected }
    RvGeoUpdate.Idle -> null
} ?: R.string.status_stopped.takeIf { !data.connected }
