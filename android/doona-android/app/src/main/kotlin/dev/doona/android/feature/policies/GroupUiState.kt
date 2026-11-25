package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvSubscription

/** The group page's actions that reach the engine; a failure names the one that failed. */
internal enum class GroupAction { Select, Apart, Test }

/** A failed [action] and its [code], shown in the banner until the next attempt of any action succeeds (plan 8). */
@Immutable
internal data class GroupFailure(val action: GroupAction, val code: ErrorCode)

/**
 * What a group's page shows: the group with its nodes ([group], null once the engine is ready and has no such group), the
 * subscriptions its nodes come from, the node each network uses with the 分開選擇 switch ([choice]), the network the next
 * tap applies to ([apply]), and for a Score group ([isScore]) Score's evidence ([score], null until read). [nowEpochMs]
 * is when it was read, for the subscriptions' end dates.
 */
@Immutable
internal data class GroupUiState(
    val loading: Boolean = true,
    val group: RvPolicyGroup? = null,
    val subscriptions: List<RvSubscription> = emptyList(),
    val choice: RvApplyChoice? = null,
    val apply: RvApplyTo = RvApplyTo.Tcp,
    val score: ScoreGroup? = null,
    val isScore: Boolean = false,
    val nowEpochMs: Long = 0L,
    val failure: GroupFailure? = null,
)
