package dev.doona.android.feature.onboarding

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.subscriptions.AddFormState

/** What the 活動 tab needs to decide between its first-run page and the activity screen. */
@Immutable
data class FirstRunUiState(
    /** False until the engine has loaded its subscriptions, so the saved ones are not mistaken for none. */
    val ready: Boolean = false,
    val hasSubscriptions: Boolean = false,
    /** The tunnel is stopped or has failed: nothing is running that the activity screen would have to show or stop. */
    val idle: Boolean = true,
    /** The core refused the last start for want of a node, which the page answers with the hint to add a subscription. */
    val noNodes: Boolean = false,
    /** Why the last start failed when the core's answer was not `no_nodes`; the page says so, as the activity screen does. */
    val failure: FailureReason? = null,
    val form: AddFormState = AddFormState(),
) {
    /**
     * The first-run page stands where the activity screen would: nothing to start from and nothing running. A tunnel
     * that runs after the last subscription was removed keeps its status and its switch.
     */
    val showsFirstRun: Boolean get() = !hasSubscriptions && idle
}

/** What [FirstRunViewModel] handles. */
sealed interface FirstRunAction {
    /** Submits the add form. */
    data object Add : FirstRunAction

    data object DismissError : FirstRunAction
}

fun firstRunUiState(ready: Boolean, subscriptionCount: Int, status: Status, form: AddFormState): FirstRunUiState {
    val failed = status as? Status.Failed
    val noNodes = failed?.code == ErrorCode.NoNodes
    return FirstRunUiState(
        ready = ready,
        hasSubscriptions = subscriptionCount > 0,
        idle = status is Status.Stopped || failed != null,
        noNodes = noNodes,
        failure = failed?.reason?.takeUnless { noNodes },
        form = form,
    )
}
