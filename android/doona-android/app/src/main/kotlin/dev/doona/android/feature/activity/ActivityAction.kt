package dev.doona.android.feature.activity

import dev.doona.android.engine.Mode

/** What [ActivityViewModel] handles; connecting and navigation are separate callbacks of the screen. */
sealed interface ActivityAction {
    data object Disconnect : ActivityAction

    data class SetMode(val mode: Mode) : ActivityAction

    /** Probes every group's members: 活動's 延遲 action, and 重新測試 when every node failed. */
    data object TestLatency : ActivityAction

    /** Tries the failed action of [RvActivityFailure] again. */
    data object Retry : ActivityAction
}
