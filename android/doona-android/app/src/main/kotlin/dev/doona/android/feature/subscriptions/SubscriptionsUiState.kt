package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription

/**
 * A subscription as the page shows it: the engine's value, whether an update of it is running, whether its expiry has
 * passed, and the code of its last failed refresh. [Subscription.error] is an [ErrorCode] name, as `vpn/` maps it; one this
 * build does not know is [ErrorCode.Internal].
 */
@Immutable
data class SubscriptionItem(
    val subscription: Subscription,
    val updating: Boolean = false,
    val expired: Boolean = false,
    val problem: ErrorCode? = null,
)

/** Everything the subscriptions page shows, in engine terms; the screen formats and localizes it. */
@Immutable
data class SubscriptionsUiState(
    /** False until the engine has loaded, so an empty list is not shown as "no subscriptions" too early. */
    val ready: Boolean = false,
    val items: List<SubscriptionItem> = emptyList(),
    /** The add form is open. */
    val adding: Boolean = false,
    val form: AddFormState = AddFormState(),
    /** The subscription a removal is waiting on the user's confirmation for. */
    val removing: Subscription? = null,
    /** The last update or removal's failure, shown until dismissed or until the next attempt succeeds. */
    val error: ErrorCode? = null,
)

/** What [SubscriptionsViewModel] handles. */
sealed interface SubscriptionsAction {
    data class Update(val id: String) : SubscriptionsAction

    data class AskRemove(val id: String) : SubscriptionsAction

    data object ConfirmRemove : SubscriptionsAction

    data object CancelRemove : SubscriptionsAction

    data object OpenForm : SubscriptionsAction

    data object CloseForm : SubscriptionsAction

    /** Submits the add form. */
    data object Add : SubscriptionsAction

    data object DismissFormError : SubscriptionsAction

    data object DismissError : SubscriptionsAction
}

/** What the view model keeps besides the engine's values. */
data class SubscriptionsLocal(
    val updating: Set<String> = emptySet(),
    val adding: Boolean = false,
    val removingId: String? = null,
    val error: ErrorCode? = null,
)

fun subscriptionsUiState(
    ready: Boolean,
    subscriptions: List<Subscription>,
    form: AddFormState,
    local: SubscriptionsLocal,
    nowMs: Long,
): SubscriptionsUiState = SubscriptionsUiState(
    ready = ready,
    items = subscriptions.map { sub ->
        SubscriptionItem(
            subscription = sub,
            updating = sub.id in local.updating,
            expired = sub.expiresAtEpochMs?.let { it < nowMs } ?: false,
            problem = sub.error?.let { name -> ErrorCode.entries.firstOrNull { it.name == name } ?: ErrorCode.Internal },
        )
    },
    adding = local.adding,
    form = form,
    removing = subscriptions.firstOrNull { it.id == local.removingId },
    // An import that failed while the form was closed has no form to show it.
    error = local.error ?: form.error.takeUnless { local.adding },
)
