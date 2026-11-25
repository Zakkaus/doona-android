package dev.doona.android.feature.perapp

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.InstalledApps
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RebuildFailedException
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.prototype.rv.settings.RvPerAppData
import dev.doona.android.prototype.rv.settings.rvPerAppActions
import dev.doona.android.prototype.rv.settings.rvPerAppSample
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"

/**
 * 依 App as the app builds it (`app-rv-perapp-*`): the RV sample engine and the sample's apps, through the ViewModel and
 * the mapper, into the page. Each state is reached as a user reaches it (a policy, checks, a search, a save the engine
 * answers), and is the same state as its `proto-rv-perapp-*` golden, so the pair differs only where the engine cannot
 * carry the sample's value.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class PerAppAppShotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun allLight() = shot("all", "perapp", dark = false)

    @Test fun allDark() = shot("all", "perapp", dark = true)

    @Test fun allFs115() = shot("all", "perapp", fontScale = RV_FS115)

    @Test fun allFs200() = shot("all", "perapp", fontScale = RV_FS200)

    @Test fun onlyLight() = shot("only", dark = false)

    @Test fun onlyDark() = shot("only", dark = true)

    @Test fun onlyFs115() = shot("only", fontScale = RV_FS115)

    @Test fun onlyFs200() = shot("only", fontScale = RV_FS200)

    @Test fun exceptLight() = shot("except", dark = false)

    @Test fun exceptDark() = shot("except", dark = true)

    @Test fun exceptFs115() = shot("except", fontScale = RV_FS115)

    @Test fun exceptFs200() = shot("except", fontScale = RV_FS200)

    @Test fun searchLight() = shot("search", dark = false)

    @Test fun searchDark() = shot("search", dark = true)

    @Test fun searchFs115() = shot("search", fontScale = RV_FS115)

    @Test fun searchFs200() = shot("search", fontScale = RV_FS200)

    @Test fun noResultLight() = shot("no-result", dark = false)

    @Test fun noResultDark() = shot("no-result", dark = true)

    @Test fun noResultFs115() = shot("no-result", fontScale = RV_FS115)

    @Test fun noResultFs200() = shot("no-result", fontScale = RV_FS200)

    @Test fun loadingLight() = shot("loading", dark = false)

    @Test fun loadingDark() = shot("loading", dark = true)

    @Test fun loadingFs115() = shot("loading", fontScale = RV_FS115)

    @Test fun loadingFs200() = shot("loading", fontScale = RV_FS200)

    @Test fun noAppsLight() = shot("no-apps", dark = false)

    @Test fun noAppsDark() = shot("no-apps", dark = true)

    @Test fun noAppsFs115() = shot("no-apps", fontScale = RV_FS115)

    @Test fun noAppsFs200() = shot("no-apps", fontScale = RV_FS200)

    @Test fun noneSelectedLight() = shot("none-selected", dark = false)

    @Test fun noneSelectedDark() = shot("none-selected", dark = true)

    @Test fun noneSelectedFs115() = shot("none-selected", fontScale = RV_FS115)

    @Test fun noneSelectedFs200() = shot("none-selected", fontScale = RV_FS200)

    @Test fun savingLight() = shot("saving", dark = false)

    @Test fun savingDark() = shot("saving", dark = true)

    @Test fun savingFs115() = shot("saving", fontScale = RV_FS115)

    @Test fun savingFs200() = shot("saving", fontScale = RV_FS200)

    @Test fun saveFailedLight() = shot("save-failed", dark = false)

    @Test fun saveFailedDark() = shot("save-failed", dark = true)

    @Test fun saveFailedFs115() = shot("save-failed", fontScale = RV_FS115)

    @Test fun saveFailedFs200() = shot("save-failed", fontScale = RV_FS200)

    @Test fun rebuildFailedLight() = shot("rebuild-failed", dark = false)

    @Test fun rebuildFailedDark() = shot("rebuild-failed", dark = true)

    @Test fun rebuildFailedFs115() = shot("rebuild-failed", fontScale = RV_FS115)

    @Test fun rebuildFailedFs200() = shot("rebuild-failed", fontScale = RV_FS200)

    @Test fun rebuildNoAppsLight() = shot("rebuild-no-apps", dark = false)

    @Test fun rebuildNoAppsDark() = shot("rebuild-no-apps", dark = true)

    @Test fun rebuildNoAppsFs115() = shot("rebuild-no-apps", fontScale = RV_FS115)

    @Test fun rebuildNoAppsFs200() = shot("rebuild-no-apps", fontScale = RV_FS200)

    @Test fun readFailedLight() = shot("read-failed", dark = false)

    @Test fun readFailedDark() = shot("read-failed", dark = true)

    @Test fun readFailedFs115() = shot("read-failed", fontScale = RV_FS115)

    @Test fun readFailedFs200() = shot("read-failed", fontScale = RV_FS200)

    private fun shot(state: String, name: String = "perapp-$state", dark: Boolean = false, fontScale: Float = 1f) {
        val variant = when {
            fontScale == RV_FS115 -> "fs115"
            fontScale == RV_FS200 -> "fs200"
            dark -> "dark"
            else -> "light"
        }
        compose.appShot(state, "app-rv-$name-$variant", dark, fontScale)
    }
}

/** The same states in English, light: the app's golden, and the sample page drawn for the panels' approved column. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RV_EN)
class PerAppAppEnShotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun all() = compose.appShot("all", "app-rv-perapp-en", dark = false, fontScale = 1f)

    @Test fun allSample() = sample("all", "perapp")

    @Test fun only() = compose.appShot("only", "app-rv-perapp-only-en", dark = false, fontScale = 1f)

    @Test fun onlySample() = sample("only")

    @Test fun except() = compose.appShot("except", "app-rv-perapp-except-en", dark = false, fontScale = 1f)

    @Test fun exceptSample() = sample("except")

    @Test fun search() = compose.appShot("search", "app-rv-perapp-search-en", dark = false, fontScale = 1f)

    @Test fun searchSample() = sample("search")

    @Test fun noResult() = compose.appShot("no-result", "app-rv-perapp-no-result-en", dark = false, fontScale = 1f)

    @Test fun noResultSample() = sample("no-result")

    @Test fun loading() = compose.appShot("loading", "app-rv-perapp-loading-en", dark = false, fontScale = 1f)

    @Test fun loadingSample() = sample("loading")

    @Test fun noApps() = compose.appShot("no-apps", "app-rv-perapp-no-apps-en", dark = false, fontScale = 1f)

    @Test fun noAppsSample() = sample("no-apps")

    @Test fun noneSelected() = compose.appShot("none-selected", "app-rv-perapp-none-selected-en", dark = false, fontScale = 1f)

    @Test fun noneSelectedSample() = sample("none-selected")

    @Test fun saving() = compose.appShot("saving", "app-rv-perapp-saving-en", dark = false, fontScale = 1f)

    @Test fun savingSample() = sample("saving")

    @Test fun saveFailed() = compose.appShot("save-failed", "app-rv-perapp-save-failed-en", dark = false, fontScale = 1f)

    @Test fun saveFailedSample() = sample("save-failed")

    @Test fun rebuildFailed() = compose.appShot("rebuild-failed", "app-rv-perapp-rebuild-failed-en", dark = false, fontScale = 1f)

    @Test fun rebuildFailedSample() = sample("rebuild-failed")

    @Test fun rebuildNoApps() = compose.appShot("rebuild-no-apps", "app-rv-perapp-rebuild-no-apps-en", dark = false, fontScale = 1f)

    @Test fun rebuildNoAppsSample() = sample("rebuild-no-apps")

    @Test fun readFailed() = compose.appShot("read-failed", "app-rv-perapp-read-failed-en", dark = false, fontScale = 1f)

    @Test fun readFailedSample() = sample("read-failed")

    /** The sample page in English, as the panels' approved column: the page code is the approved code, moved. */
    private fun sample(state: String, name: String = "perapp-$state") {
        rvAnimatorScale(if (state in BUSY) 0f else 1f)
        compose.rvContent(dark = false) { RvPerAppScreen(rvPerAppSample(state), rvPerAppActions(), onBack = {}) }
        compose.drawFully()
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("build/panels/proto-rv-$name-en.png")
    }
}

/** The sample's apps, as the phone lists them. */
private val sampleApps = RvPerAppData.apps.map { InstalledApp(it.packageName, it.label) }

/** An engine on the RV sample whose `setPerApp` answers as [answer] does, storing into the sample when it says so. */
private class SampleEngine(private val fake: FakeEngine, private val answer: suspend FakeEngine.(PerApp) -> Result<Unit>) : Engine by fake {
    override suspend fun setPerApp(perApp: PerApp): Result<Unit> = fake.answer(perApp)
}

/** The states recorded at animator scale 0, so the bar rests half full. */
private val BUSY = setOf("saving", "loading")

/**
 * Draws the page for [state] from the sample engine, through [PerAppViewModel], and records golden [golden], the busy
 * states at animator scale 0 as their `proto-rv-` goldens are.
 */
private fun ComposeContentTestRule.appShot(state: String, golden: String, dark: Boolean, fontScale: Float) {
    rvAnimatorScale(if (state in BUSY) 0f else 1f)
    val fake = rvSampleEngine()
    // The phone: the sample's apps, and for `rebuild-no-apps` the two it is about to lose.
    val phone = PhoneApps(if (state == "rebuild-no-apps") sampleApps + removedApps else sampleApps)
    val saved = if (state == "all") RvPerAppData.saved.copy(policy = RvAppPolicy.All) else RvPerAppData.saved
    runBlocking { fake.setPerApp(saved.toEngine()) }
    val engine = SampleEngine(fake) { perApp ->
        when (state) {
            "saving" -> awaitCancellation()

            "save-failed" -> Result.failure(EngineException(ErrorCode.Internal))

            "rebuild-failed" -> setPerApp(perApp).let { Result.failure(RebuildFailedException(FailureReason.StartFailed)) }

            "rebuild-no-apps" -> {
                // Stored; the listed apps are removed as the tunnel comes back, so it finds none of them.
                setPerApp(perApp)
                phone.apps = sampleApps
                Result.failure(RebuildFailedException(FailureReason.NoAllowedApps))
            }

            else -> setPerApp(perApp)
        }
    }
    val apps = when (state) {
        "loading" -> NeverApps
        "no-apps" -> FakeInstalledApps(emptyList())
        "read-failed" -> FailingApps
        else -> phone
    }
    val vm = PerAppViewModel(engine, apps)
    rvContent(dark, fontScale) { PerAppPage(vm, onConnect = {}, PerAppSystem({}, {}, {}), onBack = {}) }
    waitForIdle()
    vm.reach(state)
    waitForIdle()
    if (state == "rebuild-no-apps") {
        // The user comes back to the page, which reads the phone again: the removed apps are gone from the list.
        vm.onAction(PerAppAction.Resume)
        waitForIdle()
    }
    drawFully()
    rvRowRules(golden)
    onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$golden.png")
}

/** The user's steps to [state] from 設定's sample: a policy, checks, a search, a save. */
private fun PerAppViewModel.reach(state: String) {
    fun checks(target: Set<String>) {
        val now = uiState.value.edited.packages
        (now - target).forEach { onAction(PerAppAction.Toggle(it, false)) }
        (target - now).forEach { onAction(PerAppAction.Toggle(it, true)) }
    }

    fun except() {
        onAction(PerAppAction.SetPolicy(RvPerAppData.except.policy))
        checks(RvPerAppData.except.packages)
    }
    when (state) {
        "except" -> except()

        "search" -> onAction(PerAppAction.SetQuery(RvPerAppData.QUERY))

        "no-result" -> onAction(PerAppAction.SetQuery(RvPerAppData.QUERY_NONE))

        "none-selected" -> checks(emptySet())

        "saving", "save-failed", "rebuild-failed" -> {
            except()
            onAction(PerAppAction.Save)
        }

        // Only the two apps that are about to go, saved.
        "rebuild-no-apps" -> {
            checks(RvPerAppData.gone.packages)
            onAction(PerAppAction.Save)
        }
    }
}

/** The apps the `rebuild-no-apps` phone lists until they are removed. */
private val removedApps = RvPerAppData.removed.map { InstalledApp(it.packageName, it.label) }

/** A phone whose [apps] may change between two reads. */
private class PhoneApps(@Volatile var apps: List<InstalledApp>) : InstalledApps {
    override suspend fun launchable(): List<InstalledApp> = apps
}

/** A phone that will not list its apps. */
private object FailingApps : InstalledApps {
    override suspend fun launchable(): List<InstalledApp> = throw IllegalStateException("no package manager")
}

/** A phone whose apps never finish being read. */
private object NeverApps : InstalledApps {
    override suspend fun launchable(): List<InstalledApp> = awaitCancellation()
}
