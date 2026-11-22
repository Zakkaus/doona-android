package dev.doona.android.feature.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaBarAction
import dev.doona.android.designsystem.components.DoonaConfirmDialog
import dev.doona.android.designsystem.components.DoonaConfirmKind
import dev.doona.android.designsystem.components.DoonaEmptyState
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaProgress
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.feature.messageRes
import dev.doona.android.icons.R as IconsR

/** The 訂閱 entry: builds the ViewModel and collects its state while the page is started. */
@Composable
fun SubscriptionsRoute(engine: Engine, imports: SubscriptionImports, onBack: () -> Unit) {
    val viewModel: SubscriptionsViewModel = viewModel(factory = SubscriptionsViewModel.factory(engine, imports))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SubscriptionsScreen(state, viewModel.fields, viewModel::onAction, onBack)
}

/**
 * 訂閱: one card per subscription, the add form above them while it is open (the bar's action opens it), and, with none
 * and no form, an empty state that offers adding. Removing asks first, in a dialog.
 */
@Composable
fun SubscriptionsScreen(state: SubscriptionsUiState, fields: AddFormFields, onAction: (SubscriptionsAction) -> Unit, onBack: () -> Unit) {
    DoonaPage(
        onBack = onBack,
        actions = listOf(DoonaBarAction(IconsR.drawable.sym_add_circle, stringResource(R.string.sub_add)) { onAction(SubscriptionsAction.OpenForm) }),
    ) {
        DoonaPageTitle(stringResource(R.string.sub_title))
        DoonaGap(DoonaTheme.spacing.section)
        if (state.ready) {
            Content(state, fields, onAction)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DoonaProgress() }
        }
    }
    state.removing?.let { sub ->
        DoonaConfirmDialog(
            title = stringResource(R.string.sub_remove_title),
            body = stringResource(R.string.sub_remove_body, sub.name),
            confirmLabel = stringResource(R.string.sub_remove),
            dismissLabel = stringResource(R.string.ui_cancel),
            onConfirm = { onAction(SubscriptionsAction.ConfirmRemove) },
            onDismiss = { onAction(SubscriptionsAction.CancelRemove) },
            kind = DoonaConfirmKind.Destructive,
        )
    }
}

@Composable
private fun Content(state: SubscriptionsUiState, fields: AddFormFields, onAction: (SubscriptionsAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block)) {
        state.error?.let { code ->
            DoonaErrorBanner(stringResource(code.messageRes()), stringResource(R.string.ui_dismiss), { onAction(SubscriptionsAction.DismissError) })
        }
        if (state.adding) {
            AddSubscriptionForm(
                fields = fields,
                state = state.form,
                onSubmit = { onAction(SubscriptionsAction.Add) },
                onDismissError = { onAction(SubscriptionsAction.DismissFormError) },
                onCancel = { onAction(SubscriptionsAction.CloseForm) },
            )
        } else if (state.items.isEmpty()) {
            DoonaEmptyState(
                icon = IconsR.drawable.sym_link,
                title = stringResource(R.string.sub_empty_title),
                line = stringResource(R.string.sub_empty_line),
                actionLabel = stringResource(R.string.sub_add),
                onAction = { onAction(SubscriptionsAction.OpenForm) },
            )
        }
        state.items.forEach { item ->
            SubscriptionCard(
                item,
                onUpdate = { onAction(SubscriptionsAction.Update(item.subscription.id)) },
                onRemove = { onAction(SubscriptionsAction.AskRemove(item.subscription.id)) },
            )
        }
    }
}
