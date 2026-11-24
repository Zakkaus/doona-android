package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind

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

/** The sample's newer release. */
internal const val RV_SAMPLE_RELEASE = "0.2.0"

/**
 * The 檢查更新 round action's label: 已是最新 once the check found nothing newer, as 活動's action names its state
 * ([dev.doona.android.prototype.rv.RvAction] fades the new word through the old); 檢查更新 otherwise.
 */
@Composable
internal fun rvUpdateLabel(update: RvUpdate): String =
    stringResource(if (update == RvUpdate.Latest) R.string.rv_update_latest else R.string.rv_settings_check_update)

/**
 * The page's banner for a check that needs the user: a newer release, whose action opens its GitHub page ([onRelease]),
 * or a check that got no answer, whose action checks again ([onRetry]). Nothing while it runs or when nothing is newer.
 */
@Composable
internal fun rvUpdateNotice(update: RvUpdate, current: String, onRelease: () -> Unit, onRetry: () -> Unit): RvNotice? = when (update) {
    is RvUpdate.Available -> RvNotice(
        RvNoticeKind.Info,
        stringResource(R.string.rv_update_new, update.version),
        stringResource(R.string.rv_update_new_reason, current),
        stringResource(R.string.rv_update_view),
        onRelease,
    )

    RvUpdate.Failed -> RvNotice(
        RvNoticeKind.Error,
        stringResource(R.string.rv_update_failed),
        stringResource(R.string.rv_update_offline),
        stringResource(R.string.rv_retry),
        onRetry,
    )

    RvUpdate.Idle, RvUpdate.Checking, RvUpdate.Latest -> null
}
