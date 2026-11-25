package dev.doona.android.feature.subscriptions

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wired subscription pages and the welcome page (`app-rv-<screen>-*`, plan §2.4): RvSampleEngine through each
 * page's route, view model and mapper, in zh light, dark, fs200 and en light, each set against its `proto-rv` golden.
 * 新增訂閱 is driven as the user drives it: the link and name typed, 新增 tapped. The engine answers each import at once,
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

    @Test fun addLight() = add("sub-add", Answer.None, typed = false)

    @Test fun addDark() = add("sub-add", Answer.None, typed = false, dark = true)

    @Test fun addFs200() = add("sub-add", Answer.None, typed = false, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun addEn() = add("sub-add", Answer.None, typed = false, english = true)

    @Test fun importingLight() = add("sub-adding-unknown", Answer.Never)

    @Test fun importingDark() = add("sub-adding-unknown", Answer.Never, dark = true)

    @Test fun importingFs200() = add("sub-adding-unknown", Answer.Never, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun importingEn() = add("sub-adding-unknown", Answer.Never, english = true)

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

    /** 新增訂閱 over an engine that answers [answer]; with [typed] the sample's link and name are typed and 新增 tapped. */
    private fun add(name: String, answer: Answer, typed: Boolean = true, dark: Boolean = false, fontScale: Float = 1f, english: Boolean = false) {
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
                    onAllNodes(hasSetTextAction())[0].performTextReplacement(RV_SAMPLE_LINK)
                    onAllNodes(hasSetTextAction())[1].performTextReplacement(RvData.subscriptions.first().name)
                    onNodeWithText(string(R.string.rv_add_import)).performClick()
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
    private fun Detail() {
        val engine = remember { rvSampleEngine() }
        SubDetailRoute(engine, TWELVE_HOURS, engine.subscriptions.value.first().id, onBack = {})
    }

    @Composable
    private fun Add(engine: Engine) = SubAddRoute(engine, rememberImports(engine), onBack = {})

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
            Answer.None, Answer.Harbor -> Result.success(sample.subscriptions.value.first())
            Answer.Never -> awaitCancellation()
            is Answer.Fails -> Result.failure(EngineException(answer.code))
        }
    }

    private companion object {
        const val APP = "app-rv"

        /** A subscription's page is taller than the phone, as in the prototype's golden. */
        const val WHOLE_DETAIL = "+h1087dp"

        /** The settings' default interval. */
        val TWELVE_HOURS = MutableStateFlow(12)

        fun clipboard(): ClipboardManager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)

        fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
    }
}
