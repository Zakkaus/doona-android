package dev.doona.android.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.app.nativeApi
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Status
import dev.doona.android.feature.subscriptions.ImportState
import dev.doona.android.feature.subscriptions.STOP_MS
import dev.doona.android.feature.subscriptions.SubscriptionImports
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.NativeCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Whether the 活動 tab shows the welcome page: only once the engine has loaded ([ready]), so the saved subscriptions are
 * not mistaken for none, while there is no subscription and no node at all ([anyNode] false: nodes written into the
 * configuration keep 活動; null while not known yet), and no tunnel runs, so removing the last subscription never hides
 * a running tunnel's status or its switch. Before [ready] the tab shows 活動, which draws its own loading state.
 */
internal fun showsWelcome(ready: Boolean, subscriptions: Int, anyNode: Boolean?, status: Status): Boolean =
    ready && subscriptions == 0 && anyNode == false && (status is Status.Stopped || status is Status.Failed)

/**
 * The first run's state holder (plan §2.2). honk pushes nothing, so it is asked again once one of [imports] adds a
 * subscription: the engine's catalog does not show what an import added to honk.
 */
internal class FirstRunViewModel(
    engine: Engine,
    api: NativeApi,
    imports: StateFlow<ImportState>,
    io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    /** Whether the catalog holds a node; honk is asked, on [io], only while no subscription is there. */
    private val anyNode: Flow<Boolean?> = combine(engine.catalog, imports.map { it.added }.distinctUntilChanged()) { catalog, _ -> catalog }.map { catalog ->
        when {
            catalog == null -> null

            catalog.subscriptions.isNotEmpty() -> true

            else -> try {
                withContext(io) { NativeCatalog(api).nodes().isNotEmpty() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    val welcome: StateFlow<Boolean> =
        combine(engine.ready, engine.subscriptions, anyNode, engine.status) { ready, subscriptions, anyNode, status ->
            showsWelcome(ready, subscriptions.size, anyNode, status)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)
}

/**
 * The 活動 tab's content: the welcome page ([RvWelcome]) on the first run, whose 新增訂閱 runs [onAdd], and [content],
 * 活動, otherwise (see [showsWelcome]); an add by [imports] hands over to 活動. [frame] holds the welcome page (the app's tab frame with its nav); [content]
 * draws its own.
 */
@Composable
internal fun FirstRunGate(
    engine: Engine,
    imports: SubscriptionImports,
    onAdd: () -> Unit,
    frame: @Composable (@Composable () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val api = nativeApi()
    val viewModel: FirstRunViewModel = viewModel(factory = viewModelFactory { initializer { FirstRunViewModel(engine, api, imports.state) } })
    FirstRunGate(viewModel, onAdd, frame, content)
}

/** The 活動 tab's content on a given [viewModel], as [FirstRunGate] draws it, for a caller (a test) that makes its own. */
@Composable
internal fun FirstRunGate(
    viewModel: FirstRunViewModel,
    onAdd: () -> Unit,
    frame: @Composable (@Composable () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val welcome by viewModel.welcome.collectAsStateWithLifecycle()
    if (welcome) frame { RvWelcome(onAdd) } else content()
}
