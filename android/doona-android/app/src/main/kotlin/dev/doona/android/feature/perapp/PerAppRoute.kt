package dev.doona.android.feature.perapp

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
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
 * activity, which asks for consent when it is needed; 重試 on the rebuild notice calls it. 新增訂閱 on that notice calls
 * [onAddSubscription] (the app opens 訂閱), 檢查更新 calls [onCheckUpdate] (the app opens the releases page), and 開啟 VPN
 * 設定 opens the system's VPN settings from the page's activity.
 */
@Composable
fun PerAppRoute(
    engine: Engine,
    installedApps: InstalledApps,
    onConnect: () -> Unit,
    onAddSubscription: () -> Unit,
    onCheckUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: PerAppViewModel = viewModel(factory = PerAppViewModel.factory(engine, installedApps))
    val activity = LocalActivity.current
    val system = remember(activity, onAddSubscription, onCheckUpdate) {
        PerAppSystem(addSubscription = onAddSubscription, checkUpdate = onCheckUpdate, vpnSettings = { activity?.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) })
    }
    PerAppPage(viewModel, onConnect, system, onBack)
}

/**
 * Where the page sends the user to mend a failed rebuild: 訂閱 ([addSubscription]), the releases page ([checkUpdate]) and
 * the system's VPN settings ([vpnSettings]).
 */
internal class PerAppSystem(val addSubscription: () -> Unit, val checkUpdate: () -> Unit, val vpnSettings: () -> Unit)

/** The page on [viewModel]'s state, collected while the page is started; the goldens drive their own ViewModel through it. */
@Composable
internal fun PerAppPage(viewModel: PerAppViewModel, onConnect: () -> Unit, system: PerAppSystem, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The first resume reads the device's apps; each later one reads them again, since an app may have come or gone.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAction(PerAppAction.Resume) }
    val actions = remember(viewModel, onConnect, system) {
        RvPerAppActions(
            policy = { viewModel.onAction(PerAppAction.SetPolicy(it)) },
            toggle = { pkg, on -> viewModel.onAction(PerAppAction.Toggle(pkg, on)) },
            query = { viewModel.onAction(PerAppAction.SetQuery(it)) },
            save = { viewModel.onAction(PerAppAction.Save) },
            reconnect = {
                viewModel.onAction(PerAppAction.Reconnect)
                onConnect()
            },
            addSubscription = {
                viewModel.onAction(PerAppAction.AddSubscription)
                system.addSubscription()
            },
            editList = { viewModel.onAction(PerAppAction.EditList) },
            reload = { viewModel.onAction(PerAppAction.Reload) },
            checkUpdate = {
                viewModel.onAction(PerAppAction.CheckUpdate)
                system.checkUpdate()
            },
            vpnSettings = {
                viewModel.onAction(PerAppAction.VpnSettings)
                system.vpnSettings()
            },
        )
    }
    RvPerAppScreen(state, actions, onBack)
}
