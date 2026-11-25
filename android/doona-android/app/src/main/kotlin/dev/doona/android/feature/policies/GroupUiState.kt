package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.nodes.ui.RvApplyTo

/** The group page's actions that reach the engine, and its first read; a failure names the one that failed. */
internal enum class GroupAction { Read, Select, Apart, Test }

/** A failed [action] and its [code], shown in the banner until the next attempt of any action succeeds (plan 8). */
@Immutable
internal data class GroupFailure(val action: GroupAction, val code: ErrorCode)

/**
 * What a group's page shows: the group with its nodes ([group], null once the engine is ready and has no such group), the
 * subscriptions its nodes come from, the node each network uses with the 分開選擇 switch ([choice]), the network the next
 * tap applies to ([apply]), and whether Score picks the nodes ([isScore]) with what it compared ([score], honk's `scoreState`). [nowEpochMs] is when it was read, for the subscriptions' end dates; [testing] while the group's latency test runs.
 */
@Immutable
internal data class GroupUiState(
    val loading: Boolean = true,
    val group: RvPolicyGroup? = null,
    val subscriptions: List<RvSubscription> = emptyList(),
    val choice: RvApplyChoice? = null,
    val apply: RvApplyTo = RvApplyTo.Tcp,
    val isScore: Boolean = false,
    val score: ScoreGroup? = null,
    val nowEpochMs: Long = 0L,
    val testing: Boolean = false,
    val failure: GroupFailure? = null,
)
