package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvNavEnd
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvSecondaryButton
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsRow
import dev.doona.android.designsystem.rv.RvSettingsSection
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.rvConfigLine
import dev.doona.android.designsystem.rv.rvRowPress
import dev.doona.android.feature.model.RvGeoData

/**
 * Where 檢查更新 stands: not run yet, asking GitHub for the latest release, nothing newer than the app, a newer
 * [Available.version], or no answer (offline). The app downloads nothing: a newer release opens its GitHub page.
 */
@Immutable
internal sealed interface RvUpdate {
    data object Idle : RvUpdate

    data object Checking : RvUpdate

    data object Latest : RvUpdate

    data class Available(val version: String) : RvUpdate

    data object Failed : RvUpdate
}

/**
 * The label of 設定's 檢查更新 round action, once it is wired: 已是最新 once the check found nothing newer, as 活動's action names its state
 * ([dev.doona.android.designsystem.rv.RvAction] fades the new word through the old); 檢查更新 otherwise.
 */
@Composable
internal fun rvUpdateLabel(update: RvUpdate): String =
    stringResource(if (update == RvUpdate.Latest) R.string.rv_update_latest else R.string.rv_settings_check_update)

/** What the action draws: [rvUpdateLabel], but Updates for 檢查更新 so that Export beside it keeps its size; [rvUpdateLabel] is still what it reads. */
@Composable
internal fun rvUpdateActionLabel(update: RvUpdate): String =
    if (update == RvUpdate.Latest) rvUpdateLabel(update) else stringResource(R.string.rv_settings_check_update_short)

/**
 * The page's banner for a newer release, whose action opens its GitHub page ([onRelease]). Nothing otherwise: a check
 * that got no answer says so on its row only, in the error hue with 重試 (owner-fix3 item 27).
 */
@Composable
internal fun rvUpdateNotice(update: RvUpdate, current: String, onRelease: () -> Unit): RvNotice? = when (update) {
    is RvUpdate.Available -> RvNotice(
        RvNoticeKind.Info,
        stringResource(R.string.rv_update_new, update.version),
        stringResource(R.string.rv_update_new_reason, current),
        stringResource(R.string.rv_update_view),
        onRelease,
    )

    RvUpdate.Idle, RvUpdate.Checking, RvUpdate.Latest, RvUpdate.Failed -> null
}

/**
 * 設定's 更新 group, just before 關於 (owner-fix3 item 27): 檢查更新 for the app ([RvUpdateRow]), then the GeoIP and
 * GeoSite data the rules read ([RvGeoDataRow]), the two things 設定 updates.
 */
@Composable
internal fun RvUpdateGroup(version: String, update: RvUpdate, onCheck: () -> Unit, geoData: RvGeoData, onGeoUpdate: () -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_updates))
    RvSettingsCard {
        RvUpdateRow(update, version, onCheck)
        RvGeoDataRow(geoData, onGeoUpdate)
    }
}

/**
 * 檢查更新 as a row, the whole row the target: the app's [version] as its second line, then where the check stands,
 * after the dot: nothing yet, 已是最新 or the newer version, and the row checks again ([onCheck]). While it asks, the busy
 * bar at its end and no tap, as 回報問題's busy row while it writes. A check that got no answer says 無法檢查更新 in the
 * error hue and puts 重試 at the row's end, as the GeoIP/GeoSite row says its failure, with no banner. A newer release
 * is also 設定's banner.
 */
@Composable
internal fun RvUpdateRow(update: RvUpdate, version: String, onCheck: () -> Unit) {
    val label = stringResource(R.string.rv_settings_check_update)
    // Each part whole, so a line that wraps at a large font size breaks after the dot, never inside 已是最新.
    val line = rvConfigLine(listOfNotNull(version, updateState(update)))
    if (update == RvUpdate.Checking) {
        RvSettingsRow(label, icon = R.drawable.rv_settings_ic_update, subtitle = line) { RvBusy(Modifier.width(RvSize.busy)) }
    } else if (update == RvUpdate.Failed) {
        val retry: @Composable () -> Unit = { RvSecondaryButton(stringResource(R.string.rv_retry), onCheck, Modifier.width(IntrinsicSize.Max)) }
        RvSettingsRow(label, icon = R.drawable.rv_settings_ic_update, subtitle = line, error = true, action = retry)
    } else {
        val press = Modifier.rvRowPress(Role.Button, onCheck)
        RvSettingsRow(label, press, R.drawable.rv_settings_ic_update, subtitle = line) { RvNavEnd(null) }
    }
}

/** What follows the version on 檢查更新's second line. */
@Composable
private fun updateState(update: RvUpdate): String? = when (update) {
    RvUpdate.Latest -> stringResource(R.string.rv_update_latest)
    is RvUpdate.Available -> stringResource(R.string.rv_update_new, update.version)
    RvUpdate.Failed -> stringResource(R.string.rv_update_failed)
    RvUpdate.Idle, RvUpdate.Checking -> null
}
