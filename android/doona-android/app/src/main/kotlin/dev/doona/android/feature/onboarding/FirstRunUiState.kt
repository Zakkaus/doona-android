package dev.doona.android.feature.onboarding

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.subscriptions.AddFormState

/** What the 活動 tab needs to decide between its first-run page and the activity screen. */
@Immutable
data class FirstRunUiState(
    /** False until the engine has loaded its subscriptions, so the saved ones are not mistaken for none. */
    val ready: Boolean = false,
    val hasSubscriptions: Boolean = false,
    /**
     * The last start failed. With no subscription the core has no node to start with and answers `no_nodes`, which the
     * engine reports as [FailureReason.StartFailed]; the page answers with the hint to add a subscription.
     */
    val startFailed: Boolean = false,
    val form: AddFormState = AddFormState(),
)

/** What [FirstRunViewModel] handles. */
sealed interface FirstRunAction {
    /** Submits the add form. */
    data object Add : FirstRunAction

    data object DismissError : FirstRunAction
}

fun firstRunUiState(ready: Boolean, subscriptionCount: Int, status: Status, form: AddFormState): FirstRunUiState = FirstRunUiState(
    ready = ready,
    hasSubscriptions = subscriptionCount > 0,
    startFailed = (status as? Status.Failed)?.reason == FailureReason.StartFailed,
    form = form,
)
