package dev.doona.android.prototype.rv

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.preferredFrameRate
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.connections.RvConnectionDetail
import dev.doona.android.prototype.rv.connections.RvConnectionsPage
import dev.doona.android.prototype.rv.connections.RvConnectionsRoutes
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.fastest
import dev.doona.android.prototype.rv.gallery.RvGalleryScreen
import dev.doona.android.prototype.rv.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.nodes.RvNodePicker
import dev.doona.android.prototype.rv.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.RvNodesPageState
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyPreview
import dev.doona.android.prototype.rv.policy.RvPolicyPreviewRoutes
import dev.doona.android.prototype.rv.policy.RvPolicyPreviewState
import dev.doona.android.prototype.rv.settings.RvDnsScreen
import dev.doona.android.prototype.rv.settings.RvExport
import dev.doona.android.prototype.rv.settings.RvLogLevel
import dev.doona.android.prototype.rv.settings.RvReportScreen
import dev.doona.android.prototype.rv.settings.RvSettingsRoutes
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvSettingsState
import dev.doona.android.prototype.rv.settings.RvThemeScreen
import dev.doona.android.prototype.rv.settings.previewDark
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.settings.scheme
import dev.doona.android.prototype.rv.states.RvActivityNotice
import dev.doona.android.prototype.rv.states.RvActivityNoticeCard
import dev.doona.android.prototype.rv.states.rvNoticeActivity
import dev.doona.android.prototype.rv.stats.RvStatsScreen
import dev.doona.android.prototype.rv.stats.rvStatsSample
import dev.doona.android.prototype.rv.subs.RvImportSheet
import dev.doona.android.prototype.rv.subs.RvSubsRoute
import dev.doona.android.prototype.rv.subs.RvSubsRoutes
import dev.doona.android.prototype.rv.welcome.RvWelcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A launcher shortcut the sample was opened by ([action], as in xml/rv_shortcuts): 連線 and 中斷連線 run on screen as
 * soon as it shows (in the app, connecting goes through VpnService's consent first), 節點 starts with the picker open.
 */
internal enum class RvLaunch(val action: String?) {
    None(null),
    Connect("dev.doona.android.prototype.rv.CONNECT"),
    Disconnect("dev.doona.android.prototype.rv.DISCONNECT"),
    Nodes("dev.doona.android.prototype.rv.NODES"),
}

/**
 * How the sample starts: [connected] or not, whether connecting [fails], the [page] on top of its tab home (a route
 * such as `nodes` or `settings`) or a whole stack of [routes] (such as `policy`, `rules`), whether a latency [test] runs
 * a second after launch (for recording it), the shortcut it was [launch]ed by, whether 全部規則 holds two [pending]
 * quick rules, and whether the bar shows the proposed fourth tab, 連線 ([fourTabs]).
 */
@Immutable
internal data class RvSampleStart(
    val connected: Boolean = true,
    val fails: Boolean = false,
    val page: String? = null,
    val test: Boolean = false,
    val launch: RvLaunch = RvLaunch.None,
    val routes: List<String>? = null,
    val pending: Boolean = false,
    val fourTabs: Boolean = false,
) {
    val stack: List<String>
        get() = routes?.takeIf { it.isNotEmpty() } ?: when (page) {
            null, ACTIVITY -> listOf(ACTIVITY)
            SETTINGS -> listOf(SETTINGS)
            else -> listOf(ACTIVITY, page)
        }
}

/**
 * The whole sample as [RvPreviewActivity] shows it, moving as [options] say: the pages on [RvStackHost], 活動 with its
 * pull-down to the node picker, and what the sample runs by itself ([RvSampleRun]). The theme follows 主題's choice and
 * cross-fades when it changes.
 */
@Composable
internal fun RvPreviewApp(options: RvMotionOptions = RvMotionOptions(), start: RvSampleStart = RvSampleStart()) {
    var settings by remember { mutableStateOf(rvSampleSettings()) }
    val systemDark = isSystemInDarkTheme()
    // Every layer that moves votes for the display's high rate while it moves: with no vote, an adaptive-rate panel
    // (API 35 and later) runs the app at its normal rate, 60 Hz. Ambient loops vote normal where they run (RvBusy, the
    // map's pulse); a frame where nothing moves votes nothing.
    Box(Modifier.fillMaxSize().preferredFrameRate(FrameRateCategory.High), propagateMinConstraints = true) {
        RvLookFade(settings.appearance.previewDark(systemDark) to settings.appearance) { (dark, appearance) ->
            RvTheme(appearance.scheme(rvSystemScheme(dark), dark), dark) {
                RvSample(options, start, settings) { settings = it }
            }
        }
    }
}

@Composable
private fun RvSample(options: RvMotionOptions, start: RvSampleStart, settings: RvSettingsState, onSettings: (RvSettingsState) -> Unit) {
    val scope = rememberCoroutineScope()
    val run = remember { RvSampleRun(scope, start.connected, start.fails) }
    var stack by rememberSaveable { mutableStateOf(start.stack) }
    val policy = remember { RvPolicyPreviewState(if (start.pending) RvPolicyData.pending else emptyList()) }
    // While connected the minute moves on each second.
    val running = run.status is Status.Running
    LaunchedEffect(running) { if (running) rvEverySample { run.second++ } }
    LaunchedEffect(start.test) {
        if (!start.test) return@LaunchedEffect
        delay(TEST_DELAY_MS)
        run.test()
    }
    LaunchedEffect(start.launch) {
        if (start.launch == RvLaunch.Connect) run.connect()
        if (start.launch == RvLaunch.Disconnect) run.disconnect()
    }
    val pages = RvSamplePages(push = { stack = stack + it }, pop = { stack = stack.dropLast(1) }, tab = { i -> TABS[i]?.let { stack = listOf(it) } })
    val actions = remember(run) { RvRowActions(useNode = { run.currentId = it }, testNode = run::testNode, updateSubscription = run::update, deleteSubscription = run::delete) }
    CompositionLocalProvider(
        LocalRvTesting provides run.testing,
        LocalRvTestingNodes provides run.testingNodes,
        LocalRvUpdating provides run.updating,
        LocalRvRowActions provides actions,
        LocalRvNav provides if (start.fourTabs) RV_NAV_FOUR else RV_NAV,
    ) {
        RvStackHost(stack, onBack = pages.pop, options) { route -> RvSamplePage(route, run, options, settings, onSettings, policy, pages, start.launch == RvLaunch.Nodes) }
    }
}

private class RvSamplePages(val push: (String) -> Unit, val pop: () -> Unit, val tab: (Int) -> Unit)

@Composable
private fun RvSamplePage(
    route: String,
    run: RvSampleRun,
    options: RvMotionOptions,
    settings: RvSettingsState,
    onSettings: (RvSettingsState) -> Unit,
    policy: RvPolicyPreviewState,
    pages: RvSamplePages,
    picking: Boolean,
) {
    when (route.substringBefore(':')) {
        TRAFFIC -> RvTrafficScreen(run.activity(), RvData.session, onBack = pages.pop)

        STATS -> RvStatsScreen(rvStatsSample(), onBack = pages.pop)

        // The search page: the node picker, its field the search pill's; a picked node becomes the main one.
        SEARCH -> RvNodePicker(
            run.nodes,
            RvData.subscriptions,
            run.currentId,
            onPick = {
                run.currentId = it
                pages.pop()
            },
            onDetail = { pages.push("$NODE:$it") },
            onClose = pages.pop,
            searchPage = true,
        )

        NODES -> RvNodesPage(RvNodesPageState(run.nodes, run.currentId, RvData.testTimes), onBack = pages.pop, onFilter = {}, onOpen = { pages.push("$NODE:$it") }, onAllNodes = {})

        NODE -> {
            val node = run.nodes.first { it.id == route.substringAfter(':') }
            RvNodeDetail(node, current = node.id == run.currentId, RvData.testTimes, onBack = pages.pop, onUse = { run.currentId = node.id }, onTest = {})
        }

        SETTINGS -> SettingsPage(settings, onSettings, pages)

        THEME -> RvThemeScreen(settings.appearance, { onSettings(settings.copy(appearance = it)) }, rvSystemScheme(false), rvSystemScheme(true), onBack = pages.pop)

        DNS -> RvDnsScreen(settings.dns, { onSettings(settings.copy(dns = it)) }, onBack = pages.pop)

        in RvPolicyPreviewRoutes.all -> RvPolicyPreview(route, policy, settings, pages.push, pages.pop, pages.tab)

        else -> OtherPage(route, run, options, pages, picking)
    }
}

/** 訂閱's pages, the library's gallery, the first run, 連線 and a connection's page, 回報問題, or else 活動. */
@Composable
private fun OtherPage(route: String, run: RvSampleRun, options: RvMotionOptions, pages: RvSamplePages, picking: Boolean) {
    when (route.substringBefore(':')) {
        in RvSubsRoutes.all -> RvSubsRoute(route, pages.push, pages.pop)

        GALLERY -> RvGalleryScreen(onBack = pages.pop)

        WELCOME -> RvWelcome(onAdd = { pages.push(RvSubsRoutes.ADD) })

        CONNECTIONS -> ConnectionsPage(route, pages)

        CONNECTION -> {
            val i = route.substringAfter(':').toInt()
            RvConnectionDetail(RvPolicyData.connections[i], onBack = pages.pop, onClose = pages.pop, onNewRule = { pages.push("${RvPolicyPreviewRoutes.RULE}:$i") })
        }

        REPORT -> ReportPage(route, pages)

        else -> ActivityPage(route, run, options, pages, picking)
    }
}

/** 設定: its always-on row opens the system's VPN settings, and DNS and 檢視生效設定 push their pages. */
@Composable
private fun SettingsPage(settings: RvSettingsState, onSettings: (RvSettingsState) -> Unit, pages: RvSamplePages) {
    val context = LocalContext.current
    val routes = RvSettingsRoutes(
        alwaysOn = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
        dns = { pages.push(DNS) },
        effectiveConfig = { pages.push(RvPolicyPreviewRoutes.EFFECTIVE) },
        report = { pages.push(REPORT) },
    )
    RvSettingsScreen(settings, onChange = onSettings, onOpenTheme = { pages.push(THEME) }, onTab = pages.tab, routes = routes)
}

/**
 * 回報問題; `report:exporting` and `report:failed` open it in those states. An export writes for [RV_EXPORT_MS], then is
 * done, as the share sheet would take it from there.
 */
@Composable
private fun ReportPage(route: String, pages: RvSamplePages) {
    var level by rememberSaveable { mutableStateOf(RvLogLevel.Info) }
    var export by rememberSaveable { mutableStateOf(RvExport.entries.firstOrNull { it.name.equals(route.substringAfter(':', ""), ignoreCase = true) } ?: RvExport.Idle) }
    LaunchedEffect(export) {
        if (export != RvExport.Exporting) return@LaunchedEffect
        delay(RV_EXPORT_MS)
        export = RvExport.Idle
    }
    RvReportScreen(level, export, onLevel = { level = it }, onExport = { export = RvExport.Exporting }, onBack = pages.pop)
}

/** 連線; `connections:off` shows it with the tunnel off, and 連線 there opens it connected. */
@Composable
private fun ConnectionsPage(route: String, pages: RvSamplePages) {
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    val routes = RvConnectionsRoutes(open = { pages.push("$CONNECTION:$it") }, query = { query = it }, tab = pages.tab)
    RvConnectionsPage(RvPolicyData.connections, routes, query, connected = !route.endsWith(":off"))
}

/**
 * 活動 with its pull-down (open at first when [picking]): a picked node becomes the current one and the picker closes;
 * its page opens over 活動. [route] `state:<notice>` puts a notice on it instead ([NoticeActivity]), and `sub-import` the
 * deep link's sheet over it.
 */
@Composable
private fun ActivityPage(route: String, run: RvSampleRun, options: RvMotionOptions, pages: RvSamplePages, picking: Boolean) {
    val pull = rememberRvPullState(picking)
    // The outbound card's mode row and 查看全部 open 策略; a rule group opens its group's page there.
    val toPolicy = { pages.tab(RV_NAV_POLICY) }
    val routes = RvActivityRoutes(
        traffic = { pages.push(TRAFFIC) },
        stats = { pages.push(STATS) },
        search = { pages.push(SEARCH) },
        nodes = { pages.push(NODES) },
        node = { pages.push("$NODE:${run.currentId}") },
        tab = pages.tab,
        mode = toPolicy,
        rule = { name -> RvPolicyData.groups.firstOrNull { it.name == name }?.let { pages.push("${RvPolicyPreviewRoutes.GROUP}:${it.id}") } ?: toPolicy() },
        rules = toPolicy,
        test = run::test,
        subscriptions = { pages.push(RvSubsRoutes.LIST) },
    )
    val name = route.substringBefore(':')
    val notice = RvActivityNotice.entries.firstOrNull { name == STATE && it.route == route.substringAfter(':') }
    if (notice != null) {
        NoticeActivity(notice, routes, pages)
        return
    }
    RvPullDown(
        options.pull,
        pull,
        activity = { look ->
            val onAction: (ActivityAction) -> Unit = { if (it == ActivityAction.Disconnect) run.disconnect() }
            RvActivityScreen(run.activity(rememberShownSecond({ run.second }, look)), onAction, run::connect, routes, nodes = run.nodes, subscriptions = run.subscriptions, pull = look, hero = options.hero)
        },
        picker = { motion ->
            val pick: (String) -> Unit = {
                run.currentId = it
                motion.close()
            }
            RvNodePicker(run.nodes, RvData.subscriptions, run.currentId, pick, onDetail = { pages.push("$NODE:$it") }, onClose = motion.close, section = motion.section, stretch = motion.stretch)
        },
    )
    if (name == RvSubsRoutes.IMPORT) ImportSheet(pages)
}

/**
 * 活動 under a notice ([RvActivityNotice.route]): its action clears it (back to 活動), but the empty state's 新增訂閱 opens
 * the add page, and the crash notice's 匯出報告 opens 回報問題 exporting.
 */
@Composable
private fun NoticeActivity(notice: RvActivityNotice, routes: RvActivityRoutes, pages: RvSamplePages) {
    val empty = notice == RvActivityNotice.NoSubscription
    val onNotice = when (notice) {
        RvActivityNotice.NoSubscription -> ({ pages.push(RvSubsRoutes.ADD) })
        RvActivityNotice.Crash -> ({ pages.push("$REPORT:${RvExport.Exporting.name}") })
        else -> pages.pop
    }
    RvActivityScreen(rvNoticeActivity(notice), onAction = {}, onConnect = {}, routes, notice = { RvActivityNoticeCard(notice, onNotice) }, noticeOnly = empty)
}

/** A deep link's question over 活動 (`sub-import`): 加入 opens the add page, which imports it. */
@Composable
private fun ImportSheet(pages: RvSamplePages) {
    var importing by remember { mutableStateOf(true) }
    val sub = RvData.subscriptions.first()
    RvImportSheet(importing, sub.name, sub.host, onAdd = {
        importing = false
        pages.push("${RvSubsRoutes.ADD}:importing")
    }, onDismiss = { importing = false })
}

/**
 * The second 活動 shows: the run's, except while the pull or the picker covers 活動 (blurred, dimmed or hidden), when it
 * holds the one it had. A tick then changes nothing under the picker, so 活動 is not composed again, nor its blur and
 * glass drawn again, each second; it catches up as it comes back.
 */
@Composable
internal fun rememberShownSecond(second: () -> Int, look: RvPullLook): Int {
    val covered by remember(look) { derivedStateOf { look.progress() > 0f } }
    val held = remember { intArrayOf(0) }
    // Read only while uncovered, so that a tick under the cover does not compose 活動 again.
    if (!covered) held[0] = second()
    return held[0]
}

/**
 * What the sample runs by itself, at the sample's own pace: connecting takes [RV_CONNECT_MS] and ends connected, or
 * failed when it [fails]; disconnecting takes [RV_STOP_MS]; while connected the live minute moves on a [second] at a
 * time; a latency test runs [RV_TEST_MS] and gives every node a new result ([testNode] one node); the current node is
 * the one picked last. A row menu [update]s a subscription (as long as a test) or [delete]s it with its nodes.
 */
@Stable
internal class RvSampleRun(private val scope: CoroutineScope, connected: Boolean, private val fails: Boolean) {
    var status by mutableStateOf(if (connected) Status.Running(RvData.session.startEpochMs) else Status.Stopped)
        private set
    var second by mutableIntStateOf(0)
    var nodes by mutableStateOf(RvData.nodes)
        private set
    var currentId by mutableStateOf(RvData.CURRENT_ID)
    var testing by mutableStateOf(false)
        private set
    var testingNodes by mutableStateOf(emptySet<String>())
        private set
    var subscriptions by mutableStateOf(RvData.subscriptions)
        private set
    var updating by mutableStateOf(emptySet<String>())
        private set
    private var rounds = 0
    private var link: Job? = null

    fun connect() {
        if (status is Status.Running || status == Status.Starting) return
        link?.cancel()
        link = scope.launch {
            timed("connect") {
                status = Status.Starting
                delay(RV_CONNECT_MS)
                status = if (fails) Status.Failed(FailureReason.StartFailed) else Status.Running(RvData.nowEpochMs)
            }
        }
    }

    fun disconnect() {
        link?.cancel()
        link = scope.launch {
            timed("disconnect") {
                status = Status.Stopping
                delay(RV_STOP_MS)
                status = Status.Stopped
            }
        }
    }

    fun test() {
        if (testing) return
        testing = true
        scope.launch {
            timed("latency-test") {
                delay(RV_TEST_MS)
                rounds++
                nodes = nodes.map { it.retested(rounds) }
                testing = false
            }
        }
    }

    fun testNode(id: String) {
        if (testing || id in testingNodes) return
        testingNodes += id
        scope.launch {
            timed("latency-test-node") {
                delay(RV_TEST_MS)
                rounds++
                nodes = nodes.map { if (it.id == id) it.retested(rounds) else it }
                testingNodes -= id
            }
        }
    }

    /**
     * Runs [work] as [gesture] for [RvFrames], and on for the longest transition its last change of state starts (a
     * card's size, the hero's fade), so the frames those draw are counted too.
     */
    private suspend fun timed(gesture: String, work: suspend () -> Unit) {
        RvFrames.begin(gesture)
        try {
            work()
            delay(maxOf(RvMotion.MOVE_MS, RvMotion.HERO_FADE_MS).toLong())
        } finally {
            RvFrames.end(gesture)
        }
    }

    fun update(id: String) {
        if (id in updating) return
        updating += id
        scope.launch {
            delay(RV_TEST_MS)
            updating -= id
        }
    }

    /** Removes the subscription and its nodes; when the main node goes with them, the fastest left takes its place. */
    fun delete(id: String) {
        val gone = subscriptions.firstOrNull { it.id == id } ?: return
        subscriptions = subscriptions - gone
        nodes = nodes.filterNot { it.subscription == gone.name }
        if (nodes.none { it.id == currentId }) nodes.fastest(1).firstOrNull()?.let { currentId = it.id }
    }

    /** 活動 as the run has it at [second]: live while connected; otherwise its state with no rates and no history. */
    fun activity(second: Int = this.second): ActivityUiState {
        val live = rvSampleActivity(currentId, nodes, second)
        return if (status is Status.Running) live else live.copy(status = status, downRate = 0, upRate = 0, downHistory = emptyList(), upHistory = emptyList())
    }
}

/** The node after one more test: the oldest result drops off and a new one, within [SWING] ms of the last, comes in. */
private fun RvNode.retested(round: Int): RvNode {
    val last = latencyMs ?: medianMs
    val next = last?.let { (it + (id.hashCode() + round * SWING_STEP).mod(SWING * 2 + 1) - SWING).coerceAtLeast(1) }
    return copy(history = history.drop(1) + next)
}

/** The sample's own pace (not motion): a connect, a disconnect, a latency test, and the wait before a launched test. */
internal const val RV_CONNECT_MS = 1_200L
internal const val RV_STOP_MS = 400L
internal const val RV_TEST_MS = 1_200L
private const val RV_EXPORT_MS = 1_500L
private const val TEST_DELAY_MS = 1_000L
private const val SWING = 12
private const val SWING_STEP = 7

internal const val ACTIVITY = "activity"
internal const val TRAFFIC = "traffic"
internal const val STATS = "stats"
internal const val SEARCH = "search"
internal const val NODES = "nodes"
internal const val NODE = "node"
internal const val SETTINGS = "settings"
internal const val THEME = "theme"
internal const val DNS = "dns"
internal const val REPORT = "report"
internal const val CONNECTIONS = "connections"
internal const val CONNECTION = "connection"
internal const val GALLERY = "gallery"
internal const val WELCOME = "welcome"
internal const val STATE = "state"
private val TABS = mapOf(RV_NAV_ACTIVITY to ACTIVITY, RV_NAV_POLICY to RvPolicyPreviewRoutes.POLICY, RV_NAV_SETTINGS to SETTINGS, RV_NAV_CONNECTIONS to CONNECTIONS)
