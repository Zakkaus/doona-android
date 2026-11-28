package dev.doona.android.prototype.rv

import android.content.Context
import android.os.Bundle
import android.os.Trace
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import dev.doona.android.app.RvPushMotion
import dev.doona.android.app.RvTabMotion
import dev.doona.android.designsystem.rv.RvFrames
import dev.doona.android.designsystem.rv.RvHeroSwipe
import dev.doona.android.designsystem.rv.RvInter
import dev.doona.android.designsystem.rv.RvInterDisplay
import dev.doona.android.designsystem.rv.RvNoto
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.rvWarmDotMap
import dev.doona.android.feature.activity.RvActivityNotice
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyPreviewRoutes
import dev.doona.android.prototype.rv.subs.RvSubsRoutes
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Debug builds only: the Revolut-style sample with its invented state, full screen on a phone, so the glass blur and
 * the motion can be seen where the platform draws them (Robolectric's goldens cannot). 活動 pushes 流量詳情 (a traffic
 * tile), 統計 (the top bar), 節點 (the search pill and the outbound row) and the node in use (its row), and pulls down
 * the node picker; 節點 pushes a node's page; the nav switches to 設定, which pushes 主題, and to 策略, which pushes a
 * group's page (its rows), the main node's picker (its card) and the quick rule (新增規則); 活動's mode row and 查看全部
 * switch to 策略, and a rule group opens its group's page. 策略 also pushes all rules (which open the effective config)
 * and 範本 with its impact review; 設定 pushes DNS and the effective config, and its always-on row opens the system's VPN
 * settings; 連線 pushes a connection's page, which opens the quick rule from it.
 * Back returns, predictively. Start it from the launcher's "doona RV sample", or with
 * `adb shell am start -n dev.doona.android/dev.doona.android.prototype.rv.RvPreviewActivity`, adding any of:
 * `--es pull P2` (P1-P3, [RvPullMotion]), `--es tab T2` ([RvTabMotion]), `--es push S2` ([RvPushMotion]),
 * `--es hero H1` (活動's hero swipes between 全部流量 and 主節點, [RvHeroSwipe]),
 * `--es start idle` (start disconnected), `--ez fail true` (connecting fails), `--es page nodes` (start on a page over
 * 活動: nodes, stats, traffic, `gallery` (the library's parts), `welcome` (the first run), `state:<notice>` (活動 empty
 * or under a banner), `subs` (訂閱), or settings on its own; `--es route` is the same), `--es rv.route policy,rules`
 * (that stack of pages), `--ez rv.pending true` (全部規則 holds two quick rules), `--ez rv.first true` (the first run:
 * 連線 explains the VPN, the first connection the notification, before each is asked) and `--ez test true` (a latency
 * test a second after launch). The launcher icon's shortcuts (xml/rv_shortcuts) open it with an action, [RvLaunch]:
 * `-a dev.doona.android.prototype.rv.CONNECT` starts disconnected and connects on screen, `.DISCONNECT` starts connected
 * and disconnects, `.NODES` starts with the node picker open. A recreated activity does not run its action again.
 * A route whose index or id names nothing opens 活動 instead.
 */
class RvPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val options = RvMotionOptions(
            pull = extra(EXTRA_PULL, RvPullMotion.entries) { it.code } ?: RvPullMotion.Sheet,
            tab = extra(EXTRA_TAB, RvTabMotion.entries) { it.code } ?: RvTabMotion.Cut,
            push = extra(EXTRA_PUSH, RvPushMotion.entries) { it.code } ?: RvPushMotion.Zoom,
            hero = extra(EXTRA_HERO, RvHeroSwipe.entries) { it.code } ?: RvHeroSwipe.Off,
        )
        val launch = RvLaunch.entries.firstOrNull { it.action != null && it.action == intent.action }.takeIf { savedInstanceState == null } ?: RvLaunch.None
        val start = RvSampleStart(
            connected = when (launch) {
                RvLaunch.Connect -> false
                RvLaunch.Disconnect -> true
                else -> intent.getStringExtra(EXTRA_START) != START_IDLE
            },
            fails = intent.getBooleanExtra(EXTRA_FAIL, false),
            page = intent.getStringExtra(EXTRA_PAGE) ?: intent.getStringExtra(EXTRA_ROUTE),
            test = intent.getBooleanExtra(EXTRA_TEST, false),
            launch = launch,
            routes = intent.getStringExtra(EXTRA_STACK)?.split(',')?.filter { it.isNotBlank() },
            pending = intent.getBooleanExtra(EXTRA_PENDING, false),
            firstRun = intent.getBooleanExtra(EXTRA_FIRST, false),
        )
        preloadFonts(applicationContext)
        rvWarmDotMap()
        // Each gesture's frame times go to logcat (`adb logcat -s RvFrames`).
        setContent {
            DisposableEffect(window) { onDispose(RvFrames.attach(window)) }
            RvPreviewApp(options, start)
        }
    }

    /** The launch's [key] extra as one of [entries], by its name or its [code], any case; null when absent or unknown. */
    private fun <T : Enum<T>> extra(key: String, entries: List<T>, code: (T) -> String): T? = intent.getStringExtra(key)?.let { value ->
        entries.firstOrNull { it.name.equals(value, ignoreCase = true) || code(it).equals(value, ignoreCase = true) }
    }
}

/**
 * Loads every weight of the sample's fonts on a thread of its own as the activity starts, so that no frame waits on a
 * font file: Compose loads a resource font on the main thread, during the first layout that needs its weight. The
 * typefaces land in caches every resolver shares. Traced as [FONTS].
 */
private fun preloadFonts(context: Context) = thread(name = FONTS, isDaemon = true) {
    Trace.beginSection(FONTS)
    try {
        val resolver = createFontFamilyResolver(context)
        runBlocking { listOf(RvInter, RvInterDisplay, RvNoto).forEach { resolver.preload(it) } }
    } finally {
        Trace.endSection()
    }
}

/** The options to compare, one change from the default each: the default (P1 T1 S1 H0), then P2, P3, T2, S2 and H1. */
internal class RvMotionPreviews : PreviewParameterProvider<RvMotionOptions> {
    override val values = sequenceOf(
        RvMotionOptions(),
        RvMotionOptions(pull = RvPullMotion.Stretch),
        RvMotionOptions(pull = RvPullMotion.Drop),
        RvMotionOptions(tab = RvTabMotion.Fade),
        RvMotionOptions(push = RvPushMotion.Axis),
        RvMotionOptions(hero = RvHeroSwipe.On),
    )
}

/** Run in Android Studio's interactive preview to try each option by hand. */
@Preview(name = "Motion", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun RvMotionPreview(@PreviewParameter(RvMotionPreviews::class) options: RvMotionOptions) = RvPreviewApp(options)

private const val FONTS = "RvFontPreload"
private const val EXTRA_PULL = "pull"
private const val EXTRA_TAB = "tab"
private const val EXTRA_PUSH = "push"
private const val EXTRA_HERO = "hero"
private const val EXTRA_START = "start"
private const val START_IDLE = "idle"
private const val EXTRA_FAIL = "fail"
private const val EXTRA_PAGE = "page"
private const val EXTRA_ROUTE = "route"
private const val EXTRA_STACK = "rv.route"
private const val EXTRA_PENDING = "rv.pending"
private const val EXTRA_FIRST = "rv.first"
private const val EXTRA_TEST = "test"

/**
 * How the sample starts: [connected] or not, whether connecting [fails], the [page] on top of its tab home (a route
 * such as `nodes` or `settings`) or a whole stack of [routes] (such as `policy`, `rules`), whether a latency [test] runs
 * a second after launch (for recording it), the shortcut it was [launch]ed by, whether 全部規則 holds two [pending]
 * quick rules, and whether it is the [firstRun], when 連線 explains the VPN and the first connection the notification
 * before each is asked ([RvPermissionAsks]).
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
    val firstRun: Boolean = false,
) {
    val stack: List<String>
        get() {
            val start = routes?.takeIf { it.isNotEmpty() } ?: when (page) {
                null, ACTIVITY -> listOf(ACTIVITY)
                SETTINGS -> listOf(SETTINGS)
                else -> listOf(ACTIVITY, page)
            }
            val policy = RvPolicyPreviewRoutes
            // A route's index or id must name something; an unknown route draws 活動 already.
            val valid = start.all { route ->
                val name = route.substringBefore(':')
                val arg = route.substringAfter(':', "")
                val index = arg.toIntOrNull() ?: -1
                when (name) {
                    CONNECTION -> index in RvPolicyData.connections.indices

                    // The count is read from assets on the page, which shows nothing for an index past the end.
                    LICENSE -> index >= 0

                    policy.EDIT -> index in 0 until RvPolicyData.allRules.lastIndex

                    policy.RULE -> route == name || index in RvPolicyData.connections.indices

                    NODE -> RvData.nodes.any { it.id == arg }

                    RvSubsRoutes.DETAIL -> RvData.subscriptions.any { it.id == arg }

                    policy.GROUP -> RvPolicyData.main.id == arg || RvPolicyData.groups.any { it.id == arg }

                    STATE -> RvActivityNotice.entries.any { it.route == arg }

                    else -> true
                }
            }
            if (!valid) return listOf(ACTIVITY)
            return if (start.first() in TABS.values) start else listOf(ACTIVITY) + start
        }
}
