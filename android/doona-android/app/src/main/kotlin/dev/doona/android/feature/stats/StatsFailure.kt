package dev.doona.android.feature.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.messageRes

/** The tools of 統計 whose failure shows as a banner (plan 8, M8). */
internal enum class StatsTool { TestAll, UpdateAll }

/** A tool that failed: which one, the engine's [code], and for 更新所有訂閱 the [subscription] that failed first. */
@Immutable
internal data class StatsFailure(val tool: StatsTool, val code: ErrorCode, val subscription: String? = null)

/**
 * The banner a failed tool leaves at the top of 統計, as on the other pages (pv-d M8): 無法測試延遲 or 無法更新 <name>, over
 * the engine's own reason, and 重試 ([onRetry]).
 */
@Composable
internal fun rvStatsFailureBanner(failure: StatsFailure, onRetry: () -> Unit): RvNotice {
    val title = when (failure.tool) {
        StatsTool.TestAll -> stringResource(R.string.rv_failed_test_latency)
        StatsTool.UpdateAll -> stringResource(R.string.rv_sub_failed_title, failure.subscription.orEmpty())
    }
    return RvNotice(
        RvNoticeKind.Error,
        title,
        stringResource(failure.code.messageRes()),
        stringResource(R.string.rv_retry),
        onRetry,
        // 無法更新 <name>: a subscription's name keeps the platform's breaks; ICU's word breaks would split it.
        titleKeepWords = failure.tool != StatsTool.UpdateAll,
    )
}
