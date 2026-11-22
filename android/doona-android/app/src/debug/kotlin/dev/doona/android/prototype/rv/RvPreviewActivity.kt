package dev.doona.android.prototype.rv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.nodes.RvNodePickerPage
import dev.doona.android.prototype.rv.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.sampleNodesPage
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyGroupPage
import dev.doona.android.prototype.rv.policy.RvPolicyRoutes
import dev.doona.android.prototype.rv.policy.RvPolicyRulePage
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import dev.doona.android.prototype.rv.policy.RvQuickRule
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvThemeScreen
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.stats.RvStatsScreen
import dev.doona.android.prototype.rv.stats.rvStatsSample

/**
 * Debug builds only: the Revolut-style sample with its invented state, full screen on a phone, so the glass blur can be
 * seen where the platform draws it (Robolectric's goldens cannot). 活動 pushes 流量詳情 (a traffic tile), 統計 (the top
 * bar), 節點 (the search pill and the outbound row) and the node in use (its row); 節點 pushes a node's page; the nav
 * switches to 設定, which pushes 主題, and to 策略, which pushes a group's page (its rows), the main node's picker (its
 * card) and the quick rule (新增規則); 活動's mode row and 查看全部 switch to 策略, and a rule group opens its group's page. Back returns. Start it from the launcher's "doona RV sample", or with
 * `adb shell am start -n dev.doona.android/dev.doona.android.prototype.rv.RvPreviewActivity`.
 */
class RvPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            RvTheme(rvSystemScheme(dark), dark) {
                // The pages pushed over the tab home, last on top: a route's name, and a node's id after a colon.
                var stack by rememberSaveable { mutableStateOf(listOf(ACTIVITY)) }
                BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
                RvPreviewPage(stack.last(), push = { stack = stack + it }, pop = { stack = stack.dropLast(1) }, tab = { stack = listOf(it) })
            }
        }
    }
}

@Composable
private fun RvPreviewPage(route: String, push: (String) -> Unit, pop: () -> Unit, tab: (String) -> Unit) {
    val nodes = remember { sampleNodesPage() }
    var settings by remember { mutableStateOf(rvSampleSettings()) }
    var policy by remember { mutableStateOf(RvPolicyState()) }
    var quickRule by remember { mutableStateOf(RvQuickRule()) }
    val onTab: (Int) -> Unit = { i -> TABS[i]?.let(tab) }
    when (route.substringBefore(':')) {
        TRAFFIC -> RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = pop)

        STATS -> RvStatsScreen(rvStatsSample(), onBack = pop)

        NODES -> RvNodesPage(nodes, onBack = pop, onFilter = {}, onOpen = { push("$NODE:$it") }, onAllNodes = {})

        NODE -> {
            val node = nodes.nodes.first { it.id == route.substringAfter(':') }
            RvNodeDetail(node, current = node.id == nodes.currentId, nodes.testedAtEpochMs, onBack = pop, onUse = {}, onTest = {})
        }

        SETTINGS -> RvSettingsScreen(settings, onChange = { settings = it }, onOpenTheme = { push(THEME) }, onTab = onTab)

        POLICY -> {
            val routes = RvPolicyRoutes(group = { push("$GROUP:$it") }, mainNode = { push(PICKER) }, newRule = { push(RULE) }, tab = onTab)
            RvPolicyScreen(policy, { policy = it }, routes)
        }

        PICKER -> {
            val main = policy.group(RvPolicyData.main)
            RvNodePickerPage(RvData.nodes, RvData.subscriptions, main.selectedId, onPick = { policy = policy.choose(main.id, it) }, onBack = pop, onTest = {})
        }

        GROUP -> {
            val group = policy.group(RvPolicyData.group(route.substringAfter(':')))
            RvPolicyGroupPage(group, onBack = pop, onSelect = { policy = policy.choose(group.id, it) }, onTest = {})
        }

        RULE -> RvPolicyRulePage(RvPolicyData.connection, quickRule, { quickRule = it }, onBack = pop, onApply = pop)

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
private const val POLICY = "policy"
private const val GROUP = "group"
private const val RULE = "rule"
private const val PICKER = "picker"
private val TABS = mapOf(RV_NAV_ACTIVITY to ACTIVITY, RV_NAV_POLICY to POLICY, RV_NAV_SETTINGS to SETTINGS)
