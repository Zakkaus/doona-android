package dev.doona.android.prototype.rv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider

/**
 * Debug builds only: the Revolut-style sample with its invented state, full screen on a phone, so the glass blur and
 * the motion can be seen where the platform draws them (Robolectric's goldens cannot). 活動 pushes 流量詳情 (a traffic
 * tile), 統計 (the top bar), 節點 (the search pill and the outbound row) and the node in use (its row), and pulls down
 * the node picker; 節點 pushes a node's page; the nav switches to 設定, which pushes 主題. Back returns, predictively.
 * Start it from the launcher's "doona RV sample", or with
 * `adb shell am start -n dev.doona.android/dev.doona.android.prototype.rv.RvPreviewActivity`, adding any of:
 * `--es pull P2` (P1-P3, [RvPullMotion]), `--es tab T2` ([RvTabMotion]), `--es push S2` ([RvPushMotion]),
 * `--es start idle` (start disconnected), `--ez fail true` (connecting fails), `--es page nodes` (start on a page:
 * nodes, stats, traffic or settings) and `--ez test true` (a latency test a second after launch). The launcher icon's
 * shortcuts (xml/rv_shortcuts) open it with an action, [RvLaunch]: `-a dev.doona.android.prototype.rv.CONNECT` starts
 * disconnected and connects on screen, `.DISCONNECT` starts connected and disconnects, `.NODES` starts with the node
 * picker open. A recreated activity does not run its action again.
 */
class RvPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val options = RvMotionOptions(
            pull = extra(EXTRA_PULL, RvPullMotion.entries) { it.code } ?: RvPullMotion.Sheet,
            tab = extra(EXTRA_TAB, RvTabMotion.entries) { it.code } ?: RvTabMotion.Cut,
            push = extra(EXTRA_PUSH, RvPushMotion.entries) { it.code } ?: RvPushMotion.Zoom,
        )
        val launch = RvLaunch.entries.firstOrNull { it.action != null && it.action == intent.action }.takeIf { savedInstanceState == null } ?: RvLaunch.None
        val start = RvSampleStart(
            connected = when (launch) {
                RvLaunch.Connect -> false
                RvLaunch.Disconnect -> true
                else -> intent.getStringExtra(EXTRA_START) != START_IDLE
            },
            fails = intent.getBooleanExtra(EXTRA_FAIL, false),
            page = intent.getStringExtra(EXTRA_PAGE),
            test = intent.getBooleanExtra(EXTRA_TEST, false),
            launch = launch,
        )
        // Each gesture's frame times go to logcat (`adb logcat -s RvFrames`).
        RvFrames.attach(window)
        setContent { RvPreviewApp(options, start) }
    }

    /** The launch's [key] extra as one of [entries], by its name or its [code], any case; null when absent or unknown. */
    private fun <T : Enum<T>> extra(key: String, entries: List<T>, code: (T) -> String): T? = intent.getStringExtra(key)?.let { value ->
        entries.firstOrNull { it.name.equals(value, ignoreCase = true) || code(it).equals(value, ignoreCase = true) }
    }
}

/** The options to compare, one change from the default each: the default (P1 T1 S1), then P2, P3, T2 and S2. */
internal class RvMotionPreviews : PreviewParameterProvider<RvMotionOptions> {
    override val values = sequenceOf(
        RvMotionOptions(),
        RvMotionOptions(pull = RvPullMotion.Stretch),
        RvMotionOptions(pull = RvPullMotion.Drop),
        RvMotionOptions(tab = RvTabMotion.Fade),
        RvMotionOptions(push = RvPushMotion.Axis),
    )
}

/** Run in Android Studio's interactive preview to try each option by hand. */
@Preview(name = "Motion", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun RvMotionPreview(@PreviewParameter(RvMotionPreviews::class) options: RvMotionOptions) = RvPreviewApp(options)

private const val EXTRA_PULL = "pull"
private const val EXTRA_TAB = "tab"
private const val EXTRA_PUSH = "push"
private const val EXTRA_START = "start"
private const val START_IDLE = "idle"
private const val EXTRA_FAIL = "fail"
private const val EXTRA_PAGE = "page"
private const val EXTRA_TEST = "test"
