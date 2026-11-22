package dev.doona.android.feature.onboarding

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
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaProgress
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.feature.subscriptions.AddFormFields
import dev.doona.android.feature.subscriptions.AddSubscriptionForm

/**
 * The 活動 tab's content: the progress page until the engine has loaded, the first-run page while there is no
 * subscription, and [content], the activity screen, from the first subscription on.
 */
@Composable
fun FirstRunGate(engine: Engine, content: @Composable () -> Unit) {
    val viewModel: FirstRunViewModel = viewModel(factory = FirstRunViewModel.factory(engine))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when {
        !state.ready -> LoadingScreen()
        state.hasSubscriptions -> content()
        else -> FirstRunScreen(state, viewModel.fields, viewModel::onAction)
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
 * add form, and no connect control. A start that failed anyway, such as from the quick settings tile, gets the hint to add one.
 */
@Composable
fun FirstRunScreen(state: FirstRunUiState, fields: AddFormFields, onAction: (FirstRunAction) -> Unit) {
    val space = DoonaTheme.spacing
    DoonaPage {
        DoonaPageTitle(stringResource(R.string.hub_activity))
        DoonaGap(space.section)
        Column(verticalArrangement = Arrangement.spacedBy(space.tight)) {
            DoonaText(stringResource(R.string.first_heading), style = DoonaTheme.type.cardTitle)
            DoonaText(stringResource(R.string.first_line_what), style = DoonaTheme.type.body, color = DoonaTheme.colors.text.secondary)
            DoonaText(stringResource(R.string.first_line_next), style = DoonaTheme.type.body, color = DoonaTheme.colors.text.secondary)
        }
        DoonaGap(space.section)
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            if (state.startFailed) {
                DoonaCard(Modifier.fillMaxWidth()) { DoonaText(stringResource(R.string.first_no_nodes), style = DoonaTheme.type.body) }
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
