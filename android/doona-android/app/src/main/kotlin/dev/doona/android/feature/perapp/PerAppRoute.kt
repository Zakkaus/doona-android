package dev.doona.android.feature.perapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps

/**
 * The 依 App entry: builds the ViewModel and shows the page on its state. [onConnect] starts the tunnel through the
 * activity, which asks for consent when it is needed; 重試 on the rebuild notice calls it.
 */
@Composable
fun PerAppRoute(engine: Engine, installedApps: InstalledApps, onConnect: () -> Unit, onBack: () -> Unit) {
    val viewModel: PerAppViewModel = viewModel(factory = PerAppViewModel.factory(engine, installedApps))
    PerAppPage(viewModel, onConnect, onBack)
}

/** The page on [viewModel]'s state, collected while the page is started; the goldens drive their own ViewModel through it. */
@Composable
internal fun PerAppPage(viewModel: PerAppViewModel, onConnect: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The first resume reads the device's apps; each later one reads them again, since an app may have come or gone.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAction(PerAppAction.Resume) }
    val actions = remember(viewModel, onConnect) {
        RvPerAppActions(
            policy = { viewModel.onAction(PerAppAction.SetPolicy(it)) },
            toggle = { pkg, on -> viewModel.onAction(PerAppAction.Toggle(pkg, on)) },
            query = { viewModel.onAction(PerAppAction.SetQuery(it)) },
            save = { viewModel.onAction(PerAppAction.Save) },
            reconnect = {
                viewModel.onAction(PerAppAction.Reconnect)
                onConnect()
            },
            editList = { viewModel.onAction(PerAppAction.EditList) },
            reload = { viewModel.onAction(PerAppAction.Reload) },
        )
    }
    RvPerAppScreen(state, actions, onBack)
}
