package dev.doona.android.feature.subscriptions

import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.designsystem.rv.LocalRvUpdating
import dev.doona.android.engine.Engine
import kotlinx.coroutines.flow.Flow

/**
 * 訂閱: the subscriptions, which open their page ([onOpen], also the menu's 編輯) and lead to 新增訂閱 ([onAdd]).
 * End dates count from [clock].
 */
@Composable
fun SubscriptionsRoute(
    engine: Engine,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) {
    val viewModel: SubscriptionsViewModel = viewModel(factory = viewModelFactory { initializer { SubscriptionsViewModel(engine, clock) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onBack, onOpen, onAdd) {
        RvSubsActions(onBack, onOpen, onAdd, viewModel::update, viewModel::updateAll, viewModel::delete, viewModel::confirmDelete)
    }
    CompositionLocalProvider(LocalRvUpdating provides state.updating) { RvSubsPage(state.page, actions, loading = state.loading) }
}

/**
 * 新增訂閱 over [imports], the app's import in flight; 完成 and back run [onBack]. The clipboard is asked whether it may
 * hold a link each time the page comes to the front. [lanAsking] is whether the local network prompt is up: an import
 * waiting on it leaves the form as it is under the prompt.
 */
@Composable
fun SubAddRoute(engine: Engine, imports: SubscriptionImports, lanAsking: Flow<Boolean>, onBack: () -> Unit) {
    val clipboard = LocalContext.current.applicationContext.getSystemService(ClipboardManager::class.java)
    val viewModel: SubAddViewModel = viewModel(
        factory = viewModelFactory { initializer { SubAddViewModel(engine, imports, AndroidClipboardLinks(clipboard), lanAsking) } },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val protocols by viewModel.protocols.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshClipboard()
        onPauseOrDispose {}
    }
    RvSubAdd(state, viewModel::edit, viewModel::paste, viewModel::import, onBack, protocols)
}

/**
 * Subscription [id]'s page; it leaves ([onBack]) once the subscription is gone, deleted here or elsewhere. The
 * auto-update interval in hours comes from [updateHours] (the app's settings; null while auto-update is off), and the
 * row shows no value until its first; the row runs [onAutoUpdate], which opens 更新間隔.
 */
@Composable
fun SubDetailRoute(engine: Engine, updateHours: Flow<Int?>, id: String, onBack: () -> Unit, onAutoUpdate: () -> Unit) {
    val viewModel: SubDetailViewModel = viewModel(factory = viewModelFactory { initializer { SubDetailViewModel(engine, updateHours, id) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.gone) LaunchedEffect(Unit) { onBack() }
    val sub = state.sub ?: return
    RvSubDetail(sub, state.sheet, viewModel::change, onBack, viewModel::update, viewModel::setUserAgent, state.autoUpdate, onAutoUpdate, viewModel::rename, viewModel::delete)
}
