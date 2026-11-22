package dev.doona.android.prototype.rv

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.doona.android.prototype.rv.connections.RvConnectionDetail
import dev.doona.android.prototype.rv.connections.RvConnectionsPage
import dev.doona.android.prototype.rv.connections.RvConnectionsRoutes
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.gallery.RvGalleryScreen
import dev.doona.android.prototype.rv.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.sampleNodesPage
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyPreview
import dev.doona.android.prototype.rv.policy.RvPolicyPreviewRoutes
import dev.doona.android.prototype.rv.policy.RvPolicyPreviewState
import dev.doona.android.prototype.rv.settings.RvDnsScreen
import dev.doona.android.prototype.rv.settings.RvSettingsRoutes
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvThemeScreen
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.stats.RvStatsScreen
import dev.doona.android.prototype.rv.stats.rvStatsSample

/**
 * Debug builds only: the Revolut-style sample with its invented state, full screen on a phone, so the glass blur can be
 * seen where the platform draws it (Robolectric's goldens cannot). 活動 pushes 流量詳情 (a traffic tile), 統計 (the top
 * bar), 節點 (the search pill and the outbound row) and the node in use (its row); 節點 pushes a node's page; the nav
 * switches to 設定, which pushes 主題, and to 策略, which pushes a group's page (its rows and the main node) and the quick
 * rule (新增規則); 活動's mode row and 查看全部 switch to 策略, and a rule group opens its group's page. 策略 also pushes all
 * rules (which open the effective config) and 範本 with its impact review; 設定 pushes DNS and the effective config, and
 * its always-on row opens the system's VPN settings; 連線 (the fourth tab, see [EXTRA_FOUR_TABS]) pushes a connection's
 * page, which opens the quick rule from it. Back returns. Start it from the launcher's "doona RV sample", or with
 * `adb shell am start -n dev.doona.android/dev.doona.android.prototype.rv.RvPreviewActivity`, adding the extras below.
 */
class RvPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            RvTheme(rvSystemScheme(dark), dark) {
                // The pages pushed over the tab home, last on top: a route's name, and a node's id after a colon.
                var stack by rememberSaveable { mutableStateOf(startStack(intent)) }
                val policy = remember { RvPolicyPreviewState(if (intent.getBooleanExtra(EXTRA_PENDING, false)) RvPolicyData.pending else emptyList()) }
                BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
                val tabs = if (intent.getBooleanExtra(EXTRA_FOUR_TABS, false)) RV_NAV_FOUR else RV_NAV
                CompositionLocalProvider(LocalRvNav provides tabs) {
                    RvPreviewPage(stack.last(), policy, push = { stack = stack + it }, pop = { stack = stack.dropLast(1) }, tab = { stack = listOf(it) })
                }
            }
        }
    }
}

@Composable
private fun RvPreviewPage(route: String, policy: RvPolicyPreviewState, push: (String) -> Unit, pop: () -> Unit, tab: (String) -> Unit) {
    val nodes = remember { sampleNodesPage() }
    var settings by remember { mutableStateOf(rvSampleSettings()) }
    val onTab: (Int) -> Unit = { i -> TABS[i]?.let(tab) }
    val base = route.substringBefore(':')
    if (base in RvPolicyPreviewRoutes.all) return RvPolicyPreview(route, policy, settings, push, pop, onTab)
    when (base) {
        TRAFFIC -> RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = pop)

        STATS -> RvStatsScreen(rvStatsSample(), onBack = pop)

        NODES -> RvNodesPage(nodes, onBack = pop, onFilter = {}, onOpen = { push("$NODE:$it") }, onAllNodes = {})

        NODE -> {
            val node = nodes.nodes.first { it.id == route.substringAfter(':') }
            RvNodeDetail(node, current = node.id == nodes.currentId, nodes.testedAtEpochMs, onBack = pop, onUse = {}, onTest = {})
        }

        SETTINGS -> {
            val context = LocalContext.current
            val routes = RvSettingsRoutes(
                alwaysOn = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
                dns = { push(DNS) },
                effectiveConfig = { push(RvPolicyPreviewRoutes.EFFECTIVE) },
            )
            RvSettingsScreen(settings, onChange = { settings = it }, onOpenTheme = { push(THEME) }, onTab = onTab, routes = routes)
        }

        DNS -> RvDnsScreen(settings.dns, { settings = settings.copy(dns = it) }, onBack = pop)

        CONNECTIONS -> RvConnectionsPage(RvPolicyData.connections, RvConnectionsRoutes(open = { push("$CONNECTION:$it") }, tab = onTab))

        CONNECTION -> {
            val i = route.substringAfter(':').toInt()
            RvConnectionDetail(RvPolicyData.connections[i], onBack = pop, onClose = pop, onNewRule = { push("${RvPolicyPreviewRoutes.RULE}:$i") })
        }

        GALLERY -> RvGalleryScreen(onBack = pop)

        THEME -> RvThemeScreen(settings.appearance, { settings = settings.copy(appearance = it) }, rvSystemScheme(false), rvSystemScheme(true), onBack = pop)

        else -> {
            // The outbound card's mode row and 查看全部 open 策略; a rule group opens its group's page there.
            val toPolicy = { tab(POLICY) }
            val rule = { name: Int -> RvPolicyData.groups.firstOrNull { it.name == name }?.let { push("$GROUP:${it.id}") } ?: toPolicy() }
            val routes = RvActivityRoutes({ push(TRAFFIC) }, { push(STATS) }, { push(NODES) }, { push("$NODE:${nodes.currentId}") }, onTab, toPolicy, rule, toPolicy)
            RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, routes)
        }
    }
}

private const val ACTIVITY = "activity"
private const val TRAFFIC = "traffic"
private const val STATS = "stats"
private const val NODES = "nodes"
private const val NODE = "node"
private const val SETTINGS = "settings"
private const val THEME = "theme"
private const val POLICY = RvPolicyPreviewRoutes.POLICY
private const val GROUP = RvPolicyPreviewRoutes.GROUP

/** `am start … --es rv.route policy,rules` opens that stack of pages; `--ez rv.pending true` holds two quick rules. */
private const val EXTRA_ROUTE = "rv.route"
private const val EXTRA_PENDING = "rv.pending"

/** `--ez rv.tabs4 true` shows the bar with the proposed fourth tab, 連線, on every tab home. */
private const val EXTRA_FOUR_TABS = "rv.tabs4"
private const val CONNECTIONS = "connections"
private const val DNS = "dns"
private const val GALLERY = "gallery"

/** rv-pages-a's launch extra: one route to open over 活動, such as `gallery`, the library's new parts. */
private const val EXTRA_ONE_ROUTE = "route"
private const val CONNECTION = "connection"

private fun startStack(intent: Intent): List<String> = intent.getStringExtra(EXTRA_ROUTE)?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
    ?: intent.getStringExtra(EXTRA_ONE_ROUTE)?.let { listOf(ACTIVITY, it) }
    ?: listOf(ACTIVITY)
private val TABS = mapOf(RV_NAV_ACTIVITY to ACTIVITY, RV_NAV_POLICY to POLICY, RV_NAV_SETTINGS to SETTINGS, RV_NAV_CONNECTIONS to CONNECTIONS)
