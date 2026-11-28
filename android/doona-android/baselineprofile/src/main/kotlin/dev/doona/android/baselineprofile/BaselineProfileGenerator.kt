package dev.doona.android.baselineprofile

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the baseline profile: [startup] the app's two cold starts (MainActivity, which release opens, and the RV
 * prototype on 活動 with demo traffic), also kept as the startup profile; [journeys] the screens and motions the perf
 * rounds measured on the prototype: 活動 scrolled to the rate tiles and back, 統計 pushed and popped with a predictive
 * back, 節點, 設定, 流量 scrubbed once, the first run's VPN sheet dragged and flung away, a region card's 檢視全部, and
 * a subscription's menu with its confirm sheet (cancelled, so the demo data stays as it was).
 *
 * The app is the one installed for the run, named by the `targetAppId` argument (build.gradle.kts); its screens are
 * found by their words and descriptions, so a missing one fails the run instead of recording another screen.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val app = requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId")?.ifEmpty { null }) {
        "targetAppId not passed as an instrumentation argument"
    }

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var savedLocales: String? = null

    @Before
    fun setAppLocale() {
        val output = device.executeShellCommand("cmd locale get-app-locales $app").trim()
        savedLocales = checkNotNull(Regex(""" are \[([A-Za-z0-9,-]*)]$""").find(output)) { output }.groupValues[1]
        val result = device.executeShellCommand("cmd locale set-app-locales $app --locales zh-Hant-TW")
        check(result.isBlank()) { result }
    }

    @After
    fun restoreAppLocale() {
        val locales = savedLocales ?: return
        val args = if (locales.isEmpty()) "" else " --locales $locales"
        val result = device.executeShellCommand("cmd locale set-app-locales $app$args")
        check(result.isBlank()) { result }
    }

    @Test
    fun startup() = rule.collect(packageName = app, includeInStartupProfile = true, maxIterations = MAX_ITERATIONS) {
        start(MAIN)
        await(By.text("活動"))
        start(PROTOTYPE)
        await(By.descStartsWith("下載"))
    }

    @Test
    fun journeys() = rule.collect(packageName = app, maxIterations = MAX_ITERATIONS) {
        mainTabs()
        activity()
        traffic()
        vpnSheet()
        regions()
        subscription()
    }

    /** MainActivity's tabs: 策略 and 設定, then 活動 again. */
    private fun MacrobenchmarkScope.mainTabs() {
        start(MAIN)
        for (tab in listOf("策略", "設定", "活動")) {
            await(By.text(tab)).click()
            Thread.sleep(SETTLE_MS)
        }
    }

    /** 活動 with live rates; scrolled to the tiles and back; 統計 pushed and popped; 節點; 設定; back to 活動. */
    private fun MacrobenchmarkScope.activity() {
        start(PROTOTYPE)
        await(By.descStartsWith("下載"))
        Thread.sleep(LIVE_MS)
        device.swipe(MID_X, 1700, MID_X, 1000, SLOW_STEPS)
        Thread.sleep(LIVE_MS)
        device.swipe(MID_X, 1000, MID_X, 1700, SLOW_STEPS)
        Thread.sleep(SETTLE_MS)

        await(By.desc("開啟統計")).click()
        await(By.text("統計"))
        Thread.sleep(SETTLE_MS)
        scrollDownAndUp()
        // A predictive back from the left edge, let go past the threshold.
        device.swipe(3, BACK_Y, 500, BACK_Y, SLOW_STEPS)
        await(By.desc("開啟統計"))
        Thread.sleep(SETTLE_MS)

        await(By.text("搜尋節點")).click()
        await(By.text("節點"))
        Thread.sleep(SETTLE_MS)
        scrollDownAndUp()
        device.pressBack()
        Thread.sleep(SETTLE_MS)

        await(By.text("設定")).click()
        Thread.sleep(SETTLE_MS)
        scrollDownAndUp()
        await(By.text("活動")).click()
        await(By.descStartsWith("下載"))
        Thread.sleep(SETTLE_MS)
    }

    /** 流量, its chart scrubbed once from left to right. */
    private fun MacrobenchmarkScope.traffic() {
        start(PROTOTYPE, "page" to "traffic")
        await(By.text("流量"))
        Thread.sleep(SETTLE_MS)
        val chart = await(By.text("1 分")).visibleBounds.top - CHART_ABOVE_TABS
        device.swipe(150, chart, 930, chart, SCRUB_STEPS)
        Thread.sleep(SETTLE_MS)
    }

    /** The first run's 連線 opens the VPN sheet: a short drag springs back, a flick dismisses it. */
    private fun MacrobenchmarkScope.vpnSheet() {
        start(PROTOTYPE, "start" to "idle", "rv.first" to true)
        val label = await(By.text("連線")).visibleCenter
        device.click(label.x, label.y - DISC_ABOVE_LABEL)
        val sheet = await(By.textContains("VPN")).visibleCenter
        Thread.sleep(SETTLE_MS)
        device.swipe(MID_X, sheet.y, MID_X, sheet.y + 150, SLOW_STEPS)
        Thread.sleep(SETTLE_MS)
        device.swipe(MID_X, sheet.y, MID_X, sheet.y + 400, FLING_STEPS)
        Thread.sleep(SETTLE_MS)
    }

    /** 節點 scrolled to the region cards; 檢視全部 grows a card by the rest of its rows. */
    private fun MacrobenchmarkScope.regions() {
        start(PROTOTYPE, "page" to "nodes")
        await(By.text("節點"))
        Thread.sleep(SETTLE_MS)
        val all = By.textContains("檢視全部")
        repeat(SCROLLS) {
            if (device.hasObject(all)) return@repeat
            device.swipe(MID_X, 1900, MID_X, 1000, SLOW_STEPS)
            Thread.sleep(SETTLE_MS)
        }
        await(all).click()
        Thread.sleep(SETTLE_MS)
    }

    /** 訂閱: a subscription's long-press menu, 刪除, and its confirm sheet backed out of. */
    private fun MacrobenchmarkScope.subscription() {
        start(PROTOTYPE, "rv.route" to "settings,subs")
        await(By.textStartsWith("Harbor")).longClick()
        Thread.sleep(SETTLE_MS)
        await(By.text("刪除")).click()
        Thread.sleep(SETTLE_MS)
        device.pressBack()
        Thread.sleep(SETTLE_MS)
    }

    private fun MacrobenchmarkScope.scrollDownAndUp() {
        device.swipe(MID_X, 1900, MID_X, 700, SLOW_STEPS)
        Thread.sleep(SETTLE_MS)
        device.swipe(MID_X, 700, MID_X, 1900, SLOW_STEPS)
        Thread.sleep(SETTLE_MS)
    }

    /** Starts [activity] afresh (its task cleared), with [extras]; returns once its first frame is drawn. */
    private fun MacrobenchmarkScope.start(activity: String, vararg extras: Pair<String, Any>) {
        val intent = Intent()
            .setComponent(ComponentName(app, activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        for ((key, value) in extras) {
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
        startActivityAndWait(intent)
    }

    private fun MacrobenchmarkScope.await(selector: BySelector): UiObject2 = checkNotNull(device.wait(Until.findObject(selector), WAIT_MS)) {
        "not on screen: $selector"
    }

    private companion object {
        const val MAIN = "dev.doona.android.app.MainActivity"
        const val PROTOTYPE = "dev.doona.android.prototype.rv.RvPreviewActivity"
        const val MAX_ITERATIONS = 8
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 800L
        const val LIVE_MS = 2_500L
        const val SCROLLS = 4

        // UiDevice.swipe moves one step every 5 ms.
        const val SLOW_STEPS = 60
        const val SCRUB_STEPS = 150
        const val FLING_STEPS = 8

        // The 1080 x 2410 phone the perf rounds used.
        const val MID_X = 540
        const val BACK_Y = 1300
        const val DISC_ABOVE_LABEL = 110
        const val CHART_ABOVE_TABS = 300
    }
}
