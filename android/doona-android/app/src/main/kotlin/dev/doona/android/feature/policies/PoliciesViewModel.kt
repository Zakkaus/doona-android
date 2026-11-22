package dev.doona.android.feature.policies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

// Only as far as the shell needs: the 策略 lane adds the actions and selection.
class PoliciesViewModel(engine: Engine) : ViewModel() {
    val uiState: StateFlow<PoliciesUiState> = combine(engine.groups, engine.ready, ::PoliciesUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PoliciesUiState(engine.groups.value, engine.ready.value))

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { PoliciesViewModel(engine) }
        }
    }
}

@Composable
fun PoliciesRoute(engine: Engine, onOpenGroup: (String) -> Unit) {
    val viewModel: PoliciesViewModel = viewModel(factory = PoliciesViewModel.factory(engine))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PoliciesScreen(state.groups, onOpenGroup)
}

/**
 * A group removed while its page is open, such as by a subscription update, leaves the page. Before the engine has
 * loaded, as after process death, a missing group is only not loaded yet.
 */
@Composable
fun GroupRoute(engine: Engine, groupId: String, onBack: () -> Unit) {
    val viewModel: PoliciesViewModel = viewModel(factory = PoliciesViewModel.factory(engine))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val group = state.groups.firstOrNull { it.id == groupId }
    when {
        group != null -> GroupScreen(group, onBack)
        state.loaded -> LaunchedEffect(Unit) { onBack() }
    }
}
