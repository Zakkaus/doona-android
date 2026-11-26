package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvPolicyState

/**
 * The 策略 actions that reach the engine, and the first read of the profile; a failure names the one that failed, so the
 * banner can say which.
 */
internal enum class PolicyAction { Read, Mode, Ads, Quic, MainNode, TestAll, Update, RemoveGroup }

/** A failed [action] and its [code], shown in the banner until the next attempt of any action succeeds (plan 8). */
@Immutable
internal data class PolicyFailure(val action: PolicyAction, val code: ErrorCode)

/**
 * What 策略 shows. [loading] holds the skeleton until the engine is ready and the first profile is read, or that read
 * failed, which [failure] then says; [noSubscription]
 * replaces the cards with the empty state. [state] is the page's own state: the mode and the two switches as the engine
 * has them, the order as the user left it; [profile] is everything the cards list.
 */
@Immutable
internal data class PoliciesUiState(
    val loading: Boolean = true,
    val noSubscription: Boolean = false,
    val state: RvPolicyState = RvPolicyState(),
    val profile: RvPolicyProfile? = null,
    val failure: PolicyFailure? = null,
)
