package dev.doona.android.feature.policies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Group
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

// Only as far as the shell needs: the 策略 lane adds the UiState, the actions and selection.
class PoliciesViewModel(engine: Engine) : ViewModel() {
    val groups: StateFlow<List<Group>> =
        engine.groups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), engine.groups.value)

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { PoliciesViewModel(engine) }
        }
    }
}

@Composable
fun PoliciesRoute(engine: Engine, onOpenGroup: (String) -> Unit) {
    val viewModel: PoliciesViewModel = viewModel(factory = PoliciesViewModel.factory(engine))
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    PoliciesScreen(groups, onOpenGroup)
}

/** A group removed while its page is open leaves an empty page; the 策略 lane decides how to leave it. */
@Composable
fun GroupRoute(engine: Engine, groupId: String, onBack: () -> Unit) {
    val viewModel: PoliciesViewModel = viewModel(factory = PoliciesViewModel.factory(engine))
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    groups.firstOrNull { it.id == groupId }?.let { GroupScreen(it, onBack) }
}
