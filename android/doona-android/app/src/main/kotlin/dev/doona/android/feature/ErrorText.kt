package dev.doona.android.feature

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason

/** The localized message for an engine error code; every screen shows engine errors through this. */
@StringRes
fun ErrorCode.messageRes(): Int = when (this) {
    ErrorCode.InvalidArgument -> R.string.error_invalid_argument
    ErrorCode.NotFound -> R.string.error_not_found
    ErrorCode.Unsupported, ErrorCode.DuplicateProviderName, ErrorCode.RequestTooLarge -> reasonRes()
    ErrorCode.FetchFailed -> R.string.error_fetch_failed
    ErrorCode.TooLarge -> R.string.error_too_large
    ErrorCode.ParseFailed -> R.string.error_parse_failed
    ErrorCode.NoNodes -> R.string.error_no_nodes
    ErrorCode.Busy -> R.string.error_busy
    ErrorCode.Internal -> R.string.error_internal
    ErrorCode.LocalNetwork -> R.string.error_local_network
    ErrorCode.DnsFailed -> R.string.error_dns_failed
    ErrorCode.StorageFull -> R.string.error_storage_full
    ErrorCode.WriteFailed -> R.string.error_write_failed
    ErrorCode.Conflict -> R.string.error_conflict
    ErrorCode.SourceLimit -> R.string.error_source_limit
    ErrorCode.NonNativeSource -> R.string.error_non_native_source
    ErrorCode.ActivationFailed -> R.string.error_activation_failed
}

/** Why an action failed, one sentence for each engine error code; every RV failure banner says why through this. */
@StringRes
fun ErrorCode.reasonRes(): Int = when (this) {
    ErrorCode.InvalidArgument -> R.string.rv_reason_invalid_argument
    ErrorCode.NotFound -> R.string.rv_reason_not_found
    ErrorCode.Unsupported -> R.string.rv_reason_unsupported
    ErrorCode.DuplicateProviderName -> R.string.rv_reason_duplicate_provider_name
    ErrorCode.FetchFailed -> R.string.rv_reason_fetch_failed
    ErrorCode.TooLarge -> R.string.rv_reason_too_large
    ErrorCode.RequestTooLarge -> R.string.rv_reason_request_too_large
    ErrorCode.ParseFailed -> R.string.rv_reason_parse_failed
    ErrorCode.NoNodes -> R.string.rv_reason_no_nodes
    ErrorCode.Busy -> R.string.rv_reason_busy
    ErrorCode.Internal -> R.string.rv_reason_internal
    ErrorCode.LocalNetwork -> R.string.rv_reason_local_network
    ErrorCode.DnsFailed -> R.string.rv_reason_dns_failed
    ErrorCode.StorageFull -> R.string.rv_reason_storage_full
    ErrorCode.WriteFailed -> R.string.rv_reason_write_failed
    ErrorCode.Conflict -> R.string.rv_reason_conflict
    ErrorCode.SourceLimit -> R.string.rv_reason_source_limit
    ErrorCode.NonNativeSource -> R.string.rv_reason_non_native_source
    ErrorCode.ActivationFailed -> R.string.rv_reason_activation_failed
}

/** A failure banner's line: why [code] failed, then [after], what stayed the same. */
@Composable
fun rvReasonLine(code: ErrorCode, after: String): String = stringResource(R.string.rv_sentences, stringResource(code.reasonRes()), after)

/** The code of a failed engine action; anything that is not an [EngineException] is a bug, shown as internal. */
fun Throwable.errorCode(): ErrorCode = (this as? EngineException)?.code ?: ErrorCode.Internal

/** The localized message for why the tunnel is not running; the activity page and the per-app page both show it. */
@StringRes
fun FailureReason.messageRes(): Int = when (this) {
    FailureReason.NoAllowedApps -> R.string.status_failed_no_allowed_apps
    FailureReason.StartFailed -> R.string.status_failed_start
    FailureReason.CoreStopped -> R.string.status_failed_core_stopped
    FailureReason.IncompatibleCore -> R.string.status_failed_incompatible_core
    FailureReason.Revoked -> R.string.vpn_drop_revoked
    FailureReason.LockdownEnabled -> R.string.vpn_failure_lockdown_enabled
    FailureReason.VpnNotEstablished -> R.string.vpn_failure_not_established
}
