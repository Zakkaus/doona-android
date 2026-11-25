package dev.doona.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaProgress
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.subscriptions.AddFormFields
import dev.doona.android.feature.subscriptions.AddSubscriptionForm
import dev.doona.android.feature.subscriptions.SubscriptionImports

/**
 * The 活動 tab's content: the progress page until the engine has loaded, the first-run page while there is no
 * subscription and no tunnel is running, and [content], the activity screen, otherwise: from the first subscription
 * on, and for as long as a tunnel runs, so removing the last subscription never hides its status or its switch.
 * [frame] holds the gate's own two pages (the app's tab frame with its nav); [content] draws its own.
 */
@Composable
fun FirstRunGate(engine: Engine, imports: SubscriptionImports, frame: @Composable (@Composable () -> Unit) -> Unit, content: @Composable () -> Unit) {
    val viewModel: FirstRunViewModel = viewModel(factory = FirstRunViewModel.factory(engine, imports))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when {
        !state.ready -> frame { LoadingScreen() }
        state.showsFirstRun -> frame { FirstRunScreen(state, viewModel.fields, viewModel::onAction) }
        else -> content()
    }
}

/** The 活動 title over progress: the engine has not loaded yet, so there is nothing to show but that it is working. */
@Composable
fun LoadingScreen() {
    DoonaPage {
        DoonaPageTitle(stringResource(R.string.hub_activity))
        DoonaGap(DoonaTheme.spacing.section)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DoonaProgress() }
    }
}

/**
 * First run: with no subscription there is nothing to connect to, so the tab explains that in two lines and offers the
 * add form, and no connect control. A start that failed anyway, such as from the quick settings tile, gets the hint to
 * add one when the core refused it for want of a node, and the failure's own message otherwise.
 */
@Composable
fun FirstRunScreen(state: FirstRunUiState, fields: AddFormFields, onAction: (FirstRunAction) -> Unit) {
    val space = DoonaTheme.spacing
    DoonaPage {
        DoonaPageTitle(stringResource(R.string.hub_activity))
        DoonaGap(space.section)
        Column(verticalArrangement = Arrangement.spacedBy(space.tight)) {
            DoonaText(stringResource(R.string.first_heading), style = DoonaTheme.type.blockTitle)
            DoonaText(stringResource(R.string.first_line_what), style = DoonaTheme.type.body, color = DoonaTheme.colors.textSecondary)
            DoonaText(stringResource(R.string.first_line_next), style = DoonaTheme.type.body, color = DoonaTheme.colors.textSecondary)
        }
        DoonaGap(space.section)
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            if (state.noNodes) {
                DoonaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(space.cardInset)) { DoonaText(stringResource(R.string.first_no_nodes), style = DoonaTheme.type.body) }
            }
            state.failure?.let { reason ->
                DoonaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(space.cardInset)) { DoonaText(stringResource(reason.messageRes()), style = DoonaTheme.type.body) }
            }
            AddSubscriptionForm(
                fields = fields,
                state = state.form,
                onSubmit = { onAction(FirstRunAction.Add) },
                onDismissError = { onAction(FirstRunAction.DismissError) },
            )
        }
    }
}
