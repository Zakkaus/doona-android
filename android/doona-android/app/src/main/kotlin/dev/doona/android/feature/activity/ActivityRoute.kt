package dev.doona.android.feature.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.app.nativeApi
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPullLook
import dev.doona.android.engine.Engine
import dev.doona.android.engine.NetworkState
import dev.doona.android.feature.messageRes
import dev.doona.android.report.CrashOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Where 活動 leads, the shell's to give: the page's own routes ([RvActivityRoutes] without the latency test, which the
 * ViewModel runs), and what each notice's action opens: 新增訂閱, the per-app list, the update check, the report, and
 * the system's network, notification and VPN settings. [exportReport] opens the report and hands it a callback to call
 * once the report page holds the crash's offer; only then is the offer taken down, so a route that opens nothing never
 * calls it and leaves the offer up.
 */
internal class ActivityRoutes(
    val traffic: () -> Unit,
    val stats: () -> Unit,
    val search: () -> Unit,
    val nodes: () -> Unit,
    val node: (String) -> Unit,
    val tab: (Int) -> Unit,
    val rule: (String) -> Unit,
    val rules: () -> Unit,
    val subscriptions: () -> Unit,
    val more: () -> Unit,
    val addSubscription: () -> Unit,
    val perApp: () -> Unit,
    val checkUpdate: () -> Unit,
    val exportReport: (onReportHasOffer: () -> Unit) -> Unit,
    val networkSettings: () -> Unit,
    val notifySettings: () -> Unit,
    val vpnSettings: () -> Unit,
)

/**
 * What 活動 does with the crash offer: [shown] once its notice reached the screen (it writes the disk, so the page calls
 * it on [disk], off the main thread), [dismiss] once 匯出報告 has opened the report and the report page holds the offer.
 * [ActivityRoute] takes them from `CrashOffer`.
 */
internal class ActivityCrashOffer(val shown: () -> Unit, val dismiss: () -> Unit, val disk: CoroutineContext = Dispatchers.IO)

/**
 * The 活動 entry: builds the ViewModel with [crashOffer]'s offer (none without one), the default [network] (the app's
 * `NetworkStateSource.state`) and what the user refused on the way to connecting ([refusals], which `MainActivity`
 * sets), and shows [ActivityPage].
 */
@Composable
internal fun ActivityRoute(
    engine: Engine,
    crashOffer: CrashOffer?,
    network: Flow<NetworkState>,
    refusals: ConnectRefusals,
    onConnect: () -> Unit,
    routes: ActivityRoutes,
    pull: RvPullLook = RvPullLook.None,
) {
    val offered = remember(crashOffer) { crashOffer?.offered ?: MutableStateFlow(false) }
    val online = remember(network) { steadyOnline(network.map { it.online }) }
    val viewModel: ActivityViewModel = viewModel(factory = ActivityViewModel.factory(engine, nativeApi(), offered, online, refusals))
    LifecycleStartEffect(viewModel) {
        viewModel.load()
        onStopOrDispose {}
    }
    val crash = remember(crashOffer) { crashOffer?.let { ActivityCrashOffer(it::shown, it::dismiss) } }
    ActivityPage(viewModel, crash, onConnect, routes, pull)
}

/**
 * 活動 from [viewModel]: its page state, collected while the page is started and held while the pull or the picker
 * ([pull]) covers it. Connecting needs the activity for the VPN consent, so it goes straight to [onConnect], as a
 * notice's 重新連線 and 重試 do; every other notice action leads where its cause can be mended
 * ([RvActivityNotice.remedy]). A failed action's banner, with its 重試, comes before the notice. [crash]: the crash offer
 * the crash notice marks shown and 匯出報告 dismisses.
 */
@Composable
internal fun ActivityPage(viewModel: ActivityViewModel, crash: ActivityCrashOffer?, onConnect: () -> Unit, routes: ActivityRoutes, pull: RvPullLook = RvPullLook.None) {
    val live by viewModel.ui.collectAsStateWithLifecycle()
    val ui = rememberHeldWhileCovered(pull) { live }
    val onAction = viewModel::onAction
    val page = remember(routes, viewModel) { pageRoutes(routes) { viewModel.onAction(ActivityAction.TestLatency) } }
    if (ui.failure == null && ui.notice == RvActivityNotice.Crash && crash != null) {
        // The offer reached the screen: it is not made again for this crash, even if the user leaves without exporting.
        // A failed action's banner takes the notice's place, so the offer is not shown while one stands.
        LaunchedEffect(crash) { withContext(crash.disk) { crash.shown() } }
    }
    val readError = ui.readError
    val failure = ui.failure
    val banner = when {
        failure != null -> rvActivityFailureBanner(failure) { onAction(ActivityAction.Retry) }
        ui.notice != null -> rvActivityBanner(ui.notice, remedy(ui.notice.remedy, routes, onConnect, onAction, crash))
        readError != null -> RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_failed_read_profile), stringResource(readError.messageRes()), stringResource(R.string.rv_retry), viewModel::load)
        else -> null
    }
    RvActivityScreen(
        state = ui.state,
        onAction = onAction,
        onConnect = onConnect,
        routes = page,
        nodes = ui.nodes,
        subscriptions = ui.subscriptions.takeIf { readError == null },
        pull = pull,
        banner = banner,
        noSubscription = if (ui.hasSubscriptions) null else routes.addSubscription,
        loading = ui.loading,
        rules = ui.rules,
        cards = ui.cards,
        cycleMode = null,
    )
}

private fun pageRoutes(routes: ActivityRoutes, test: () -> Unit) = RvActivityRoutes(
    traffic = routes.traffic,
    stats = routes.stats,
    search = routes.search,
    nodes = routes.nodes,
    node = routes.node,
    tab = routes.tab,
    mode = null,
    rule = routes.rule,
    rules = routes.rules,
    test = test,
    subscriptions = routes.subscriptions,
    more = routes.more,
)

/** What a notice's action does by its [remedy]; 匯出報告 opens the report first and takes the offer down once the report holds it. */
private fun remedy(remedy: ActivityRemedy, routes: ActivityRoutes, onConnect: () -> Unit, onAction: (ActivityAction) -> Unit, crash: ActivityCrashOffer?): () -> Unit = when (remedy) {
    ActivityRemedy.AddSubscription -> routes.addSubscription
    ActivityRemedy.PerApp -> routes.perApp
    ActivityRemedy.CheckUpdate -> routes.checkUpdate
    ActivityRemedy.Reconnect -> onConnect
    ActivityRemedy.Retest -> ({ onAction(ActivityAction.TestLatency) })
    ActivityRemedy.ExportReport -> ({ routes.exportReport { crash?.dismiss() } })
    ActivityRemedy.NetworkSettings -> routes.networkSettings
    ActivityRemedy.NotifySettings -> routes.notifySettings
    ActivityRemedy.VpnSettings -> routes.vpnSettings
}
