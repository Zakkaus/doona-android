package dev.doona.android.feature.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.designsystem.rv.RvPullLook
import dev.doona.android.engine.Engine
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.report.CrashOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Where 活動 leads, the shell's to give: the page's own routes ([RvActivityRoutes] without the latency test, which the
 * ViewModel runs), and what each notice's action opens: 新增訂閱, the per-app list, the update check, the report, and
 * the system's network, notification and VPN settings.
 */
internal class ActivityRoutes(
    val traffic: () -> Unit,
    val stats: () -> Unit,
    val search: () -> Unit,
    val nodes: () -> Unit,
    val node: () -> Unit,
    val tab: (Int) -> Unit,
    val mode: () -> Unit,
    val rule: (String) -> Unit,
    val rules: () -> Unit,
    val subscriptions: () -> Unit,
    val more: () -> Unit,
    val addSubscription: () -> Unit,
    val perApp: () -> Unit,
    val checkUpdate: () -> Unit,
    val exportReport: () -> Unit,
    val networkSettings: () -> Unit,
    val notifySettings: () -> Unit,
    val vpnSettings: () -> Unit,
)

/**
 * What 活動 does with the crash offer: [shown] once its notice reached the screen (it writes the disk, so the page calls
 * it off the main thread), [dismiss] on 匯出報告. [ActivityRoute] takes them from `CrashOffer`.
 */
internal class ActivityCrashOffer(val shown: () -> Unit, val dismiss: () -> Unit)

/**
 * The 活動 entry: builds the ViewModel with [crashOffer]'s offer (none without one) and shows [ActivityPage]. [nav] false
 * leaves the page's floating nav out, only for the old overload below, whose tab frame draws its own; the routing
 * hand-off removes it with that overload.
 */
@Composable
internal fun ActivityRoute(engine: Engine, crashOffer: CrashOffer?, onConnect: () -> Unit, routes: ActivityRoutes, pull: RvPullLook = RvPullLook.None, nav: Boolean = true) {
    val offered = remember(crashOffer) { crashOffer?.offered ?: MutableStateFlow(false) }
    val viewModel: ActivityViewModel = viewModel(factory = ActivityViewModel.factory(engine, offered))
    val crash = remember(crashOffer) { crashOffer?.let { ActivityCrashOffer(it::shown, it::dismiss) } }
    ActivityPage(viewModel, crash, onConnect, routes, pull, nav)
}

/**
 * 活動 from [viewModel]: its page state, collected while the page is started and held while the pull or the picker
 * ([pull]) covers it. Connecting needs the activity for the VPN consent, so it goes straight to [onConnect], as a
 * notice's 重新連線 and 重試 do; every other notice action leads where its cause can be mended
 * ([RvActivityNotice.remedy]). A failed action's banner, with its 重試, comes before the notice. [crash]: the crash offer
 * the crash notice marks shown and 匯出報告 dismisses. [nav]: see [ActivityRoute].
 */
@Composable
internal fun ActivityPage(viewModel: ActivityViewModel, crash: ActivityCrashOffer?, onConnect: () -> Unit, routes: ActivityRoutes, pull: RvPullLook = RvPullLook.None, nav: Boolean = true) {
    val live by viewModel.ui.collectAsStateWithLifecycle()
    val ui = rememberHeldWhileCovered(pull) { live }
    val onAction = viewModel::onAction
    val page = remember(routes, onAction) { pageRoutes(routes) { onAction(ActivityAction.TestLatency) } }
    if (ui.failure == null && ui.notice == RvActivityNotice.Crash && crash != null) {
        // The offer reached the screen: it is not made again for this crash, even if the user leaves without exporting.
        // A failed action's banner takes the notice's place, so the offer is not shown while one stands.
        LaunchedEffect(crash) { withContext(Dispatchers.IO) { crash.shown() } }
    }
    val failure = ui.failure
    val banner = when {
        failure != null -> rvActivityFailureBanner(failure) { onAction(ActivityAction.Retry) }
        else -> ui.notice?.let { notice -> rvActivityBanner(notice, remedy(notice.remedy, routes, onConnect, onAction, crash)) }
    }
    RvActivityScreen(
        state = ui.state,
        onAction = onAction,
        onConnect = onConnect,
        routes = page,
        nodes = ui.nodes,
        subscriptions = ui.subscriptions,
        pull = pull,
        banner = banner,
        noSubscription = if (ui.hasSubscriptions) null else routes.addSubscription,
        loading = ui.loading,
        rules = ui.rules,
        cards = ui.cards,
        nav = nav,
    )
}

/**
 * The old route's signature, which `DoonaApp` still calls inside its old tab frame: the RV page with only a rule row's
 * group ([onOpenGroup]) wired, and without its own floating nav, since the frame draws one. The orchestrator's routing
 * hand-off replaces the call with the [ActivityRoute] above and removes this overload.
 */
@Composable
fun ActivityRoute(engine: Engine, onConnect: () -> Unit, onOpenGroup: (String) -> Unit) {
    val routes = remember(onOpenGroup) {
        ActivityRoutes(
            traffic = NOT_WIRED,
            stats = NOT_WIRED,
            search = NOT_WIRED,
            nodes = NOT_WIRED,
            node = NOT_WIRED,
            tab = {},
            mode = NOT_WIRED,
            rule = onOpenGroup,
            rules = NOT_WIRED,
            subscriptions = NOT_WIRED,
            more = NOT_WIRED,
            addSubscription = NOT_WIRED,
            perApp = NOT_WIRED,
            checkUpdate = NOT_WIRED,
            exportReport = NOT_WIRED,
            networkSettings = NOT_WIRED,
            notifySettings = NOT_WIRED,
            vpnSettings = NOT_WIRED,
        )
    }
    ActivityRoute(engine, crashOffer = null, onConnect, routes, nav = false)
}

private fun pageRoutes(routes: ActivityRoutes, test: () -> Unit) = RvActivityRoutes(
    traffic = routes.traffic,
    stats = routes.stats,
    search = routes.search,
    nodes = routes.nodes,
    node = routes.node,
    tab = routes.tab,
    mode = routes.mode,
    rule = routes.rule,
    rules = routes.rules,
    test = test,
    subscriptions = routes.subscriptions,
    more = routes.more,
)

/** What a notice's action does by its [remedy]; 匯出報告 also takes the crash offer down. */
private fun remedy(remedy: ActivityRemedy, routes: ActivityRoutes, onConnect: () -> Unit, onAction: (ActivityAction) -> Unit, crash: ActivityCrashOffer?): () -> Unit = when (remedy) {
    ActivityRemedy.AddSubscription -> routes.addSubscription

    ActivityRemedy.PerApp -> routes.perApp

    ActivityRemedy.CheckUpdate -> routes.checkUpdate

    ActivityRemedy.Reconnect -> onConnect

    ActivityRemedy.Retest -> ({ onAction(ActivityAction.TestLatency) })

    ActivityRemedy.ExportReport -> (
        {
            crash?.dismiss()
            routes.exportReport()
        }
        )

    ActivityRemedy.NetworkSettings -> routes.networkSettings

    ActivityRemedy.NotifySettings -> routes.notifySettings

    ActivityRemedy.VpnSettings -> routes.vpnSettings
}
