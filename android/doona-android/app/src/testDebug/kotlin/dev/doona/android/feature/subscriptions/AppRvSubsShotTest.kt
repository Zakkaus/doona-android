package dev.doona.android.feature.subscriptions

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.doona.android.R
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.onboarding.FirstRunGate
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.gallery.RV_SAMPLE_LINK
import dev.doona.android.prototype.rv.rvShot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wired subscription pages and the welcome page (`app-rv-<screen>-*`, plan §2.4): RvSampleEngine through each
 * page's route, view model and mapper, in zh light, dark, fs200 and en light, each set against its `proto-rv` golden.
 * 新增訂閱 is driven as the user drives it: the name typed and the keyboard put away, the link taken from the clipboard's
 * row, 新增 tapped. The engine answers each import at once,
 * with the fake standing for the core's fetch (Harbor Cloud as it is in the sample, or the failure under test), or
 * never, for the import in flight.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvSubsShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun subsLight() = shot("subs") { Subs() }

    @Test fun subsDark() = shot("subs", dark = true) { Subs() }

    @Test fun subsFs200() = shot("subs", fontScale = RV_FS200, whole = false) { Subs() }

    @Test
    @Config(qualifiers = RV_EN)
    fun subsEn() = shot("subs", english = true) { Subs() }

    @Test fun subsEmptyLight() = shot("subs-empty") { Subs(FakeEngine(subscriptions = emptyList())) }

    @Test fun subsEmptyDark() = shot("subs-empty", dark = true) { Subs(FakeEngine(subscriptions = emptyList())) }

    @Test fun subsEmptyFs200() = shot("subs-empty", fontScale = RV_FS200, whole = false) { Subs(FakeEngine(subscriptions = emptyList())) }

    @Test
    @Config(qualifiers = RV_EN)
    fun subsEmptyEn() = shot("subs-empty", english = true) { Subs(FakeEngine(subscriptions = emptyList())) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailLight() = shot("sub-detail") { Detail() }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailDark() = shot("sub-detail", dark = true) { Detail() }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailFs200() = shot("sub-detail", fontScale = RV_FS200, whole = false) { Detail() }

    /** English runs half a dp past the zh page's window: the phone's height, as the variants without a whole capture. */
    @Test
    @Config(qualifiers = RV_EN)
    fun detailEn() = shot("sub-detail", english = true, whole = false) { Detail() }

    /** Auto-update off in the settings: the interval row reads 關閉 (orchestrator ruling 2026-11-26). */
    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailOffLight() = shot("sub-detail-off") { Detail(OFF) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailOffDark() = shot("sub-detail-off", dark = true) { Detail(OFF) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun detailOffFs200() = shot("sub-detail-off", fontScale = RV_FS200, whole = false) { Detail(OFF) }

    @Test
    @Config(qualifiers = RV_EN)
    fun detailOffEn() = shot("sub-detail-off", english = true, whole = false) { Detail(OFF) }

    @Test fun addLight() = add("sub-add", Answer.None, typed = false)

    @Test fun addDark() = add("sub-add", Answer.None, typed = false, dark = true)

    @Test fun addFs200() = add("sub-add", Answer.None, typed = false, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun addEn() = add("sub-add", Answer.None, typed = false, english = true)

    @Test fun importingLight() = add("sub-adding-unknown", Answer.Never, busyFrames = BUSY_FRAMES)

    @Test fun importingDark() = add("sub-adding-unknown", Answer.Never, dark = true, busyFrames = BUSY_FRAMES)

    @Test fun importingFs200() = add("sub-adding-unknown", Answer.Never, fontScale = RV_FS200, busyFrames = BUSY_FRAMES)

    @Test
    @Config(qualifiers = RV_EN)
    fun importingEn() = add("sub-adding-unknown", Answer.Never, english = true, busyFrames = BUSY_FRAMES)

    @Test fun doneLight() = add("sub-add-done", Answer.Harbor)

    @Test fun doneDark() = add("sub-add-done", Answer.Harbor, dark = true)

    @Test fun doneFs200() = add("sub-add-done", Answer.Harbor, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun doneEn() = add("sub-add-done", Answer.Harbor, english = true)

    @Test fun unreachableLight() = add("sub-add-unreachable", Answer.Fails(ErrorCode.FetchFailed))

    @Test fun unreachableDark() = add("sub-add-unreachable", Answer.Fails(ErrorCode.FetchFailed), dark = true)

    @Test fun unreachableFs200() = add("sub-add-unreachable", Answer.Fails(ErrorCode.FetchFailed), fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun unreachableEn() = add("sub-add-unreachable", Answer.Fails(ErrorCode.FetchFailed), english = true)

    @Test fun notSubscriptionLight() = add("sub-add-not-subscription", Answer.Fails(ErrorCode.ParseFailed))

    @Test fun notSubscriptionDark() = add("sub-add-not-subscription", Answer.Fails(ErrorCode.ParseFailed), dark = true)

    @Test fun notSubscriptionFs200() = add("sub-add-not-subscription", Answer.Fails(ErrorCode.ParseFailed), fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun notSubscriptionEn() = add("sub-add-not-subscription", Answer.Fails(ErrorCode.ParseFailed), english = true)

    @Test fun tooLargeLight() = add("sub-add-too-large", Answer.Fails(ErrorCode.TooLarge))

    @Test fun tooLargeDark() = add("sub-add-too-large", Answer.Fails(ErrorCode.TooLarge), dark = true)

    @Test fun tooLargeFs200() = add("sub-add-too-large", Answer.Fails(ErrorCode.TooLarge), fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun tooLargeEn() = add("sub-add-too-large", Answer.Fails(ErrorCode.TooLarge), english = true)

    @Test fun welcomeLight() = shot("welcome") { Welcome() }

    @Test fun welcomeDark() = shot("welcome", dark = true) { Welcome() }

    @Test fun welcomeFs200() = shot("welcome", fontScale = RV_FS200, whole = false) { Welcome() }

    @Test
    @Config(qualifiers = RV_EN)
    fun welcomeEn() = shot("welcome", english = true) { Welcome() }

    private fun shot(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        english: Boolean = false,
        whole: Boolean = true,
        before: ComposeContentTestRule.() -> Unit = {},
        content: @Composable () -> Unit,
    ) = compose.rvShot(
        name,
        dark,
        before = {
            before()
            waitForIdle()
            if (whole) assertWhole()
        },
        fontScale = fontScale,
        prefix = APP,
        english = english,
        content = content,
    )

    /**
     * 新增訂閱 over an engine that answers [answer]; with [typed] the sample's name is typed, the keyboard put away, the
     * link taken from the clipboard's row and 新增 tapped, so no field keeps the focus or a scrolled link. With
     * [busyFrames] the clock stops at the tap and runs that many frames, so a busy bar is drawn at a fixed point.
     */
    private fun add(
        name: String,
        answer: Answer,
        typed: Boolean = true,
        dark: Boolean = false,
        fontScale: Float = 1f,
        english: Boolean = false,
        busyFrames: Int = 0,
    ) {
        // The clipboard holds a link, as on the approved page; its content is read only if its row is tapped.
        clipboard().setPrimaryClip(ClipData.newPlainText(null, RV_SAMPLE_LINK))
        val engine = Answering(answer)
        shot(
            name,
            dark,
            fontScale,
            english,
            whole = fontScale == 1f,
            before = {
                if (typed) {
                    onAllNodes(hasSetTextAction())[1].performTextReplacement(RvData.subscriptions.first().name)
                    runOnIdle { focus.clearFocus() }
                    onNodeWithText(string(R.string.rv_add_clipboard)).performClick()
                    if (busyFrames > 0) mainClock.autoAdvance = false
                    onNodeWithText(string(R.string.rv_add_import)).performClick()
                    repeat(busyFrames) { mainClock.advanceTimeByFrame() }
                }
            },
        ) { Add(engine) }
    }

    @Composable
    private fun Subs(engine: Engine = rvSampleEngine()) {
        val imports = rememberImports(engine)
        SubscriptionsRoute(engine, imports, onBack = {}, clock = { RvData.nowEpochMs })
    }

    @Composable
    private fun Detail(hours: StateFlow<Int?> = TWELVE_HOURS) {
        val (engine, id) = remember { harborEngine().let { it to it.subscriptions.value.first().id } }
        SubDetailRoute(engine, hours, id, onBack = {})
    }

    @Composable
    private fun Add(engine: Engine) {
        val manager = LocalFocusManager.current
        SideEffect { focus = manager }
        SubAddRoute(engine, rememberImports(engine), onBack = {})
    }

    /** The page's focus, to put the keyboard away after typing; taken once the page has composed. */
    private lateinit var focus: FocusManager

    @Composable
    private fun Welcome() {
        val engine = remember { FakeEngine(subscriptions = emptyList()) }
        FirstRunGate(engine, rememberImports(engine)) {}
    }

    @Composable
    private fun rememberImports(engine: Engine): SubscriptionImports {
        val scope = rememberCoroutineScope()
        return remember(engine) { SubscriptionImports(engine, scope) }
    }

    /**
     * The sample with its first subscription, Harbor Cloud, as the core reports it: the core finds the format of what it
     * fetched (Clash YAML on the approved pages), which the sample's subscriptions do not carry.
     */
    private fun harborEngine(): FakeEngine = rvSampleEngine().apply {
        subscriptions.value = subscriptions.value.mapIndexed { i, sub -> if (i == 0) harbor(sub) else sub }
    }

    /** How the engine answers an import. */
    private sealed interface Answer {
        data object None : Answer

        /** The fetch never ends: the import stays in flight. */
        data object Never : Answer

        /** The sample's Harbor Cloud, as the core would add it from the sample's link. */
        data object Harbor : Answer

        data class Fails(val code: ErrorCode) : Answer
    }

    private class Answering(private val answer: Answer, private val sample: FakeEngine = rvSampleEngine()) : Engine by sample {
        override suspend fun addSubscription(url: String, name: String?, userAgent: String?): Result<Subscription> = when (answer) {
            Answer.None, Answer.Harbor -> Result.success(harbor(sample.subscriptions.value.first()))
            Answer.Never -> awaitCancellation()
            is Answer.Fails -> Result.failure(EngineException(answer.code))
        }
    }

    private companion object {
        const val APP = "app-rv"

        /**
         * The approved 匯入中 frame of the busy bar: 24 frames (384 ms) into its loop, the time the prototype's page
         * takes to settle after its first composition. Measured 2026-11-26: frame by frame from the tap, the 24th
         * matches `proto-rv-sub-adding-unknown-light` with no pixel apart; the bar starts at the tap here.
         */
        const val BUSY_FRAMES = 24

        /**
         * Fixture data: [sub] with the format the core would find behind the sample's link (Clash YAML on the approved
         * pages); RvSampleEngine's subscriptions carry none (a P2a request in p5-STATE).
         */
        fun harbor(sub: Subscription): Subscription = sub.copy(format = SubscriptionFormat.Clash)

        /** A subscription's page is taller than the phone, as in the prototype's golden. */
        const val WHOLE_DETAIL = "+h1087dp"

        /** The settings' default interval. */
        val TWELVE_HOURS = MutableStateFlow(12)

        /** Auto-update off. */
        val OFF = MutableStateFlow<Int?>(null)

        fun clipboard(): ClipboardManager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)

        fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
    }
}
