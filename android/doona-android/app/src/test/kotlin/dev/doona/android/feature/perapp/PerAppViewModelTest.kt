package dev.doona.android.feature.perapp

import dev.doona.android.engine.AppPolicy
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerAppViewModelTest {
    private val chrome = "com.android.chrome"
    private val telegram = "org.telegram.messenger"

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** `WhileSubscribed` only follows the engine while someone collects, as the screen does. */
    private fun TestScope.collected(engine: Engine, apps: InstalledApps = FakeInstalledApps()) = PerAppViewModel(engine, apps).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        // The route sends this when the page is first resumed, which is what reads the device's apps.
        vm.onAction(PerAppAction.Resume)
    }

    /** Apps that change between reads, as when one is installed or removed while the page waits in the background. */
    private class ChangingApps(var apps: List<InstalledApp>) : InstalledApps {
        var reads = 0
        var failure: Exception? = null

        override suspend fun launchable(): List<InstalledApp> {
            reads++
            failure?.let { throw it }
            return apps
        }
    }

    /** An engine whose save fails with [ErrorCode.Busy] while [failing] holds, and that otherwise saves into the fake. */
    private class FlakyEngine(private val fake: FakeEngine = FakeEngine(), var failing: Boolean = true) : Engine by fake {
        override suspend fun setPerApp(perApp: PerApp): Result<Unit> =
            if (failing) Result.failure(EngineException(ErrorCode.Busy)) else fake.setPerApp(perApp)
    }

    @Test
    fun `shows the saved policy and the apps of the device`() = runTest {
        val engine = FakeEngine().apply { setPerApp(PerApp(AppPolicy.ExceptListed, setOf(chrome))) }
        val state = collected(engine).uiState.value
        assertTrue(state.loaded)
        assertEquals(AppPolicy.ExceptListed, state.policy)
        assertEquals(setOf(chrome), state.checked)
        assertEquals(FakeInstalledApps.SAMPLE_APPS, state.apps)
        assertFalse("nothing is edited yet", state.canSave)
    }

    @Test
    fun `shows no list and cannot save while the apps are read`() = runTest {
        val gate = CompletableDeferred<List<InstalledApp>>()
        val vm = collected(
            FakeEngine(),
            apps = object : InstalledApps {
                override suspend fun launchable() = gate.await()
            },
        )
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.ExceptListed))
        assertFalse(vm.uiState.value.loaded)
        assertFalse("an empty list before the read is not a device without apps", vm.uiState.value.hasApps)
        assertFalse("the apps decide what can be saved, so wait for them", vm.uiState.value.canSave)

        gate.complete(FakeInstalledApps.SAMPLE_APPS)
        assertTrue(vm.uiState.value.loaded)
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `a device that cannot list its apps shows the error and stays usable for every app`() = runTest {
        val engine = FakeEngine()
        val vm = collected(
            engine,
            apps = object : InstalledApps {
                override suspend fun launchable(): List<InstalledApp> = throw IllegalStateException("no package manager")
            },
        )
        val state = vm.uiState.value
        assertTrue(state.loaded)
        assertFalse(state.hasApps)
        assertEquals(ErrorCode.Internal, state.error)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.ExceptListed))
        vm.onAction(PerAppAction.Save)
        assertEquals(AppPolicy.ExceptListed, engine.settings.value.perApp.policy)
    }

    @Test
    fun `search matches the label and the package name without regard to case`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))

        vm.onAction(PerAppAction.SetQuery("TELE"))
        assertEquals(listOf(telegram), vm.uiState.value.apps.map { it.packageName })
        vm.onAction(PerAppAction.SetQuery("com.google"))
        assertEquals(listOf("YouTube"), vm.uiState.value.apps.map { it.label })
        vm.onAction(PerAppAction.SetQuery("計算"))
        assertEquals(listOf("計算機"), vm.uiState.value.apps.map { it.label })
        vm.onAction(PerAppAction.SetQuery("  "))
        assertEquals(FakeInstalledApps.SAMPLE_APPS, vm.uiState.value.apps)
    }

    @Test
    fun `a search that matches nothing is told apart from a device without apps`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        vm.onAction(PerAppAction.SetQuery("zzz"))
        assertTrue(vm.uiState.value.apps.isEmpty())
        assertTrue(vm.uiState.value.hasApps)

        val bare = collected(FakeEngine(), apps = FakeInstalledApps(emptyList()))
        assertTrue(bare.uiState.value.apps.isEmpty())
        assertFalse(bare.uiState.value.hasApps)
    }

    @Test
    fun `a check survives a search that hides its row`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.SetQuery("telegram"))
        assertEquals(setOf(chrome), vm.uiState.value.checked)
    }

    @Test
    fun `toggling edits the draft and only a difference can be saved`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.ExceptListed))
        assertTrue(vm.uiState.value.canSave)

        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Toggle(telegram, true))
        assertEquals(setOf(chrome, telegram), vm.uiState.value.checked)
        vm.onAction(PerAppAction.Toggle(telegram, false))
        assertEquals(setOf(chrome), vm.uiState.value.checked)

        vm.onAction(PerAppAction.Toggle(chrome, false))
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.All))
        assertFalse("back at the saved policy there is nothing to save", vm.uiState.value.canSave)
    }

    @Test
    fun `an edit does not reach the engine until it is saved`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        assertEquals(PerApp(), engine.settings.value.perApp)
    }

    @Test
    fun `save hands the edits to the engine in one call and the page follows the engine again`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)

        assertEquals(PerApp(AppPolicy.OnlyListed, setOf(chrome)), engine.settings.value.perApp)
        val state = vm.uiState.value
        assertFalse(state.busy)
        assertFalse("saved, so nothing is left to save", state.canSave)
        assertNull(state.error)
        assertEquals(AppPolicy.OnlyListed, state.policy)
        assertEquals(setOf(chrome), state.checked)

        engine.setPerApp(PerApp(AppPolicy.All))
        assertEquals("an unedited page shows what the engine saves", AppPolicy.All, vm.uiState.value.policy)
    }

    @Test
    fun `an edit before the stored policy has loaded is ignored, so a save keeps what was stored`() = runTest {
        val engine = FakeEngine().apply { perAppLoaded.value = false }
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.ExceptListed))
        vm.onAction(PerAppAction.Toggle(telegram, true))
        assertFalse("the controls would show a placeholder", vm.uiState.value.policyLoaded)
        assertFalse(vm.uiState.value.canSave)
        vm.onAction(PerAppAction.Save)
        assertEquals("nothing reached the engine", PerApp(), engine.settings.value.perApp)

        // The stored policy arrives late, and is not the default.
        engine.setPerApp(PerApp(AppPolicy.OnlyListed, setOf(chrome)))
        engine.perAppLoaded.value = true
        val state = vm.uiState.value
        assertTrue(state.policyLoaded)
        assertEquals(AppPolicy.OnlyListed, state.policy)
        assertEquals(setOf(chrome), state.checked)
        assertFalse("the ignored edits left no draft", state.canSave)

        vm.onAction(PerAppAction.Toggle(telegram, true))
        vm.onAction(PerAppAction.Save)
        assertEquals(PerApp(AppPolicy.OnlyListed, setOf(chrome, telegram)), engine.settings.value.perApp)
    }

    @Test
    fun `a save that is stored but whose tunnel fails to rebuild shows the reason, not a failed write`() = runTest {
        val fake = FakeEngine()
        val engine = object : Engine by fake {
            override suspend fun setPerApp(perApp: PerApp): Result<Unit> {
                fake.setPerApp(perApp)
                return Result.failure(RebuildFailedException(FailureReason.NoAllowedApps))
            }
        }
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)

        val state = vm.uiState.value
        assertEquals(FailureReason.NoAllowedApps, state.rebuildFailure)
        assertNull("the write succeeded", state.error)
        assertFalse(state.busy)
        assertFalse("it is saved, so there is nothing left to save", state.canSave)

        vm.onAction(PerAppAction.DismissError)
        assertNull(vm.uiState.value.rebuildFailure)
    }

    @Test
    fun `a failed write is not a rebuild failure and a later save clears both`() = runTest {
        val engine = FlakyEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.ExceptListed))
        vm.onAction(PerAppAction.Save)
        assertNull(vm.uiState.value.rebuildFailure)
        assertEquals(ErrorCode.Busy, vm.uiState.value.error)

        engine.failing = false
        vm.onAction(PerAppAction.Save)
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.rebuildFailure)
    }

    @Test
    fun `the apps are read again when the page resumes, so a removed app no longer counts`() = runTest {
        val gone = InstalledApp("com.example.gone", "Gone")
        val apps = ChangingApps(listOf(gone) + FakeInstalledApps.SAMPLE_APPS)
        val vm = collected(FakeEngine(), apps)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        vm.onAction(PerAppAction.Toggle(gone.packageName, true))
        assertTrue("the only checked app is installed", vm.uiState.value.canSave)

        // The user uninstalls it while the page waits in the background.
        apps.apps = FakeInstalledApps.SAMPLE_APPS
        vm.onAction(PerAppAction.Resume)

        assertEquals(2, apps.reads)
        assertFalse(vm.uiState.value.apps.contains(gone))
        assertTrue("only checked now routes no installed app", vm.uiState.value.needsApp)
        assertFalse(vm.uiState.value.canSave)
    }

    @Test
    fun `a resume while the apps are being read does not read them twice`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val vm = collected(
            FakeEngine(),
            object : InstalledApps {
                override suspend fun launchable(): List<InstalledApp> {
                    reads++
                    gate.await()
                    return FakeInstalledApps.SAMPLE_APPS
                }
            },
        )
        vm.onAction(PerAppAction.Resume)
        assertEquals(1, reads)
        gate.complete(Unit)
        assertTrue(vm.uiState.value.loaded)
    }

    @Test
    fun `a read that fails on resume keeps the apps the page had and shows the error`() = runTest {
        val apps = ChangingApps(FakeInstalledApps.SAMPLE_APPS)
        val vm = collected(FakeEngine(), apps)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.ExceptListed))
        apps.failure = IllegalStateException("no package manager")
        vm.onAction(PerAppAction.Resume)

        assertEquals(FakeInstalledApps.SAMPLE_APPS, vm.uiState.value.apps)
        assertEquals(ErrorCode.Internal, vm.uiState.value.error)
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `a failed save keeps the edits, shows its code and can be tried again`() = runTest {
        val engine = FlakyEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)

        var state = vm.uiState.value
        assertEquals(ErrorCode.Busy, state.error)
        assertFalse(state.busy)
        assertTrue("the edits are still there to save again", state.canSave)
        assertEquals(setOf(chrome), state.checked)
        assertEquals(PerApp(), engine.settings.value.perApp)

        vm.onAction(PerAppAction.DismissError)
        assertNull(vm.uiState.value.error)

        engine.failing = false
        vm.onAction(PerAppAction.Save)
        state = vm.uiState.value
        assertNull(state.error)
        assertEquals(PerApp(AppPolicy.OnlyListed, setOf(chrome)), engine.settings.value.perApp)
    }

    @Test
    fun `a save in progress is busy, ignores a second save and keeps an edit made meanwhile`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fake = FakeEngine()
        var calls = 0
        val engine = object : Engine by fake {
            override suspend fun setPerApp(perApp: PerApp): Result<Unit> {
                calls++
                release.await()
                return fake.setPerApp(perApp)
            }
        }
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)
        assertTrue(vm.uiState.value.busy)
        assertFalse(vm.uiState.value.canSave)

        vm.onAction(PerAppAction.Save)
        vm.onAction(PerAppAction.Toggle(telegram, true))
        release.complete(Unit)

        assertEquals("the second save did not reach the engine", 1, calls)
        assertEquals(PerApp(AppPolicy.OnlyListed, setOf(chrome)), engine.settings.value.perApp)
        val state = vm.uiState.value
        assertFalse(state.busy)
        assertEquals("the edit made during the save stays", setOf(chrome, telegram), state.checked)
        assertTrue(state.canSave)
    }

    @Test
    fun `only checked with nothing checked cannot be saved and says why`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        assertTrue(vm.uiState.value.needsApp)
        assertFalse(vm.uiState.value.canSave)

        vm.onAction(PerAppAction.Save)
        assertEquals("nothing reached the engine", PerApp(), engine.settings.value.perApp)
        assertFalse(vm.uiState.value.busy)

        vm.onAction(PerAppAction.Toggle(chrome, true))
        assertFalse(vm.uiState.value.needsApp)
        assertTrue(vm.uiState.value.canSave)
        vm.onAction(PerAppAction.Toggle(chrome, false))
        assertTrue(vm.uiState.value.needsApp)
    }

    @Test
    fun `only apps that are installed count as checked for only checked`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.OnlyListed))
        vm.onAction(PerAppAction.Toggle("com.removed.app", true))
        assertTrue("the tunnel would route no app", vm.uiState.value.needsApp)
        assertFalse(vm.uiState.value.canSave)
        assertEquals("the stored package stays in the set", setOf("com.removed.app"), vm.uiState.value.checked)
    }

    @Test
    fun `except checked with nothing checked is a policy that can be saved`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(AppPolicy.ExceptListed))
        assertFalse(vm.uiState.value.needsApp)
        vm.onAction(PerAppAction.Save)
        assertEquals(PerApp(AppPolicy.ExceptListed), engine.settings.value.perApp)
    }

    @Test
    fun `packages of apps that are gone stay in the saved set`() = runTest {
        val engine = FakeEngine().apply { setPerApp(PerApp(AppPolicy.ExceptListed, setOf("com.removed.app"))) }
        val vm = collected(engine)
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)
        assertEquals(setOf("com.removed.app", chrome), engine.settings.value.perApp.packages)
    }
}
