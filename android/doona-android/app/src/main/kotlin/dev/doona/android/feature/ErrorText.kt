package dev.doona.android.feature

import androidx.annotation.StringRes
import dev.doona.android.R
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason

/** The localized message for an engine error code; every screen shows engine errors through this. */
@StringRes
fun ErrorCode.messageRes(): Int = when (this) {
    ErrorCode.InvalidArgument -> R.string.error_invalid_argument
    ErrorCode.NotFound -> R.string.error_not_found
    ErrorCode.FetchFailed -> R.string.error_fetch_failed
    ErrorCode.ParseFailed -> R.string.error_parse_failed
    ErrorCode.NoNodes -> R.string.error_no_nodes
    ErrorCode.Busy -> R.string.error_busy
    ErrorCode.Internal -> R.string.error_internal
    ErrorCode.LocalNetwork -> R.string.error_local_network
}

/** The code of a failed engine action; anything that is not an [EngineException] is a bug, shown as internal. */
fun Throwable.errorCode(): ErrorCode = (this as? EngineException)?.code ?: ErrorCode.Internal

/** The localized message for why the tunnel is not running; the activity page and the per-app page both show it. */
@StringRes
fun FailureReason.messageRes(): Int = when (this) {
    FailureReason.NoAllowedApps -> R.string.status_failed_no_allowed_apps
    FailureReason.StartFailed -> R.string.status_failed_start
    FailureReason.CoreStopped -> R.string.status_failed_core_stopped
    FailureReason.IncompatibleCore -> R.string.status_failed_incompatible_core
}
