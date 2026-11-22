package dev.doona.android.feature

import androidx.annotation.StringRes
import dev.doona.android.R
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode

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
}

/** The code of a failed engine action; anything that is not an [EngineException] is a bug, shown as internal. */
fun Throwable.errorCode(): ErrorCode = (this as? EngineException)?.code ?: ErrorCode.Internal
