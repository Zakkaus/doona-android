package dev.doona.android.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Status
import dev.doona.android.feature.subscriptions.STOP_MS
import dev.doona.android.feature.subscriptions.SubscriptionImports
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the 活動 tab shows the welcome page: only once the engine has loaded ([ready]), so the saved subscriptions are
 * not mistaken for none, while there is no subscription and no tunnel runs, so removing the last subscription never
 * hides a running tunnel's status or its switch. Before [ready] the tab shows 活動, which draws its own loading state.
 */
internal fun showsWelcome(ready: Boolean, subscriptions: Int, status: Status): Boolean =
    ready && subscriptions == 0 && (status is Status.Stopped || status is Status.Failed)

/** The first run's state holder (plan §2.2). */
internal class FirstRunViewModel(engine: Engine) : ViewModel() {
    val welcome: StateFlow<Boolean> =
        combine(engine.ready, engine.subscriptions, engine.status) { ready, subscriptions, status -> showsWelcome(ready, subscriptions.size, status) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), showsWelcome(engine.ready.value, engine.subscriptions.value.size, engine.status.value))
}

/**
 * The 活動 tab's content: the welcome page ([RvWelcome]) on the first run, whose 新增訂閱 runs [onAdd], and [content],
 * 活動, otherwise (see [showsWelcome]).
 */
@Composable
fun FirstRunGate(
    engine: Engine,
    onAdd: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val viewModel: FirstRunViewModel = viewModel(factory = viewModelFactory { initializer { FirstRunViewModel(engine) } })
    val welcome by viewModel.welcome.collectAsStateWithLifecycle()
    if (welcome) RvWelcome(onAdd) else content()
}
