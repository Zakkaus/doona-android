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
import dev.doona.android.app.nativeApi
import dev.doona.android.designsystem.rv.LocalRvUpdating
import dev.doona.android.engine.Engine
import dev.doona.android.feature.nodes.ui.LocalRvRowActions
import dev.doona.android.feature.nodes.ui.RvRowActions
import kotlinx.coroutines.flow.Flow

/**
 * 訂閱: the subscriptions, which open their page ([onOpen], also the menu's 編輯) and lead to 新增訂閱 ([onAdd]); under
 * them the 組態內 card, whose nodes open their page ([onOpenNode]) and whose 新增節點 opens its sheet. After an add the
 * banner's 查看策略群組 runs [onViewGroups]. End dates count from [clock]. The subscriptions are honk's, read through the
 * app's [nativeApi] unless a caller (a test) gives its own [viewModel].
 */
@Composable
internal fun SubscriptionsRoute(
    engine: Engine,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenNode: (String) -> Unit = {},
    onViewGroups: () -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    viewModel: SubscriptionsViewModel = nativeApi().let { api -> viewModel(factory = viewModelFactory { initializer { SubscriptionsViewModel(api, clock) } }) },
) {
    val clipboard = LocalContext.current.applicationContext.getSystemService(ClipboardManager::class.java)
    val nodes: AddNodeViewModel = viewModel(factory = viewModelFactory { initializer { AddNodeViewModel(engine, AndroidClipboardLinks(clipboard)) } })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val inline by nodes.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, nodes, onBack, onOpen, onAdd) {
        // Anything else asked of the page puts the last add's banner away.
        RvSubsActions(
            back = onBack,
            open = onOpen,
            add = onAdd,
            update = {
                nodes.dismissAdded()
                viewModel.update(it)
            },
            updateAll = {
                nodes.dismissAdded()
                viewModel.updateAll()
            },
            delete = {
                nodes.dismissAdded()
                viewModel.delete(it)
            },
            confirmDelete = viewModel::confirmDelete,
        )
    }
    val inlineActions = remember(nodes, onOpenNode, onViewGroups) {
        RvInlineActions(nodes::open, nodes::close, nodes::edit, nodes::paste, nodes::add, onOpenNode, nodes::delete, nodes::confirmDelete, onViewGroups)
    }
    val banner = inline.deleteFailed?.let { rvNodeDeleteFailedBanner(it) { nodes.confirmDelete(it.id) } } ?: inline.added?.let { rvAddedBanner(it, onViewGroups) }
    // No 複製分享連結: the core keeps a share link only for a share-link subscription's node, never a 組態內 one.
    CompositionLocalProvider(LocalRvUpdating provides state.updating, LocalRvRowActions provides remember(nodes) { RvRowActions(deleteNode = nodes::delete) }) {
        RvSubsPage(state.page, actions, loading = state.loading, extra = { RvInlineCard(inline.nodes, inlineActions) }, extraKeepsPage = inline.nodes.isNotEmpty(), banner = banner, addNode = inlineActions.add)
        RvAddNodeSheet(inline.sheet, inlineActions)
        RvNodeDeleteSheet(inline.nodes.firstOrNull { it.id == inline.deletingId }, inlineActions)
    }
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
 * row shows no value until its first; the row runs [onAutoUpdate], which opens 更新間隔. The subscription is honk's,
 * read through the app's [nativeApi] unless a caller (a test) gives its own [viewModel].
 */
@Composable
internal fun SubDetailRoute(
    updateHours: Flow<Int?>,
    id: String,
    onBack: () -> Unit,
    onAutoUpdate: () -> Unit,
    viewModel: SubDetailViewModel = nativeApi().let { api -> viewModel(factory = viewModelFactory { initializer { SubDetailViewModel(api, updateHours, id) } }) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.gone) LaunchedEffect(Unit) { onBack() }
    val sub = state.sub ?: return
    RvSubDetail(sub, state.sheet, viewModel::change, onBack, viewModel::update, viewModel::setUserAgent, state.autoUpdate, onAutoUpdate, viewModel::rename, viewModel::delete)
}
