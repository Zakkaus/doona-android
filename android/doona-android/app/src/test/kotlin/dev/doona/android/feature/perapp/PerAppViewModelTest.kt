package dev.doona.android.feature.perapp

import dev.doona.android.R
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
import dev.doona.android.feature.FailureFix
import dev.doona.android.feature.model.RvAppPolicy
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

/** The page's ViewModel on the fake engine: what it shows (the RV page's state) and what reaches the engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class PerAppViewModelTest {
    private val chrome = "com.android.chrome"
    private val telegram = "org.telegram.messenger"
    private val sample = FakeInstalledApps.SAMPLE_APPS.map { RvApp(it.label, it.packageName) }

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

    private val PerAppViewModel.state get() = uiState.value
    private val RvPerAppState.policy get() = edited.policy
    private val RvPerAppState.checked get() = edited.packages
    private val RvPerAppState.loaded get() = installed != null

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

    /** An engine whose save fails with [code] while [failing] holds, and that otherwise saves into the fake. */
    private class FlakyEngine(
        private val fake: FakeEngine = FakeEngine(),
        var failing: Boolean = true,
        val code: ErrorCode = ErrorCode.Busy,
    ) : Engine by fake {
        override suspend fun setPerApp(perApp: PerApp): Result<Unit> =
            if (failing) Result.failure(EngineException(code)) else fake.setPerApp(perApp)
    }

    /** An engine that stores every save and then reports that the tunnel did not come back, for [reason] and [code]. */
    private class RebuildFails(private val fake: FakeEngine = FakeEngine(), val reason: FailureReason, val code: ErrorCode? = null) : Engine by fake {
        override suspend fun setPerApp(perApp: PerApp): Result<Unit> {
            fake.setPerApp(perApp)
            return Result.failure(RebuildFailedException(reason, code))
        }
    }

    @Test
    fun `shows the saved policy and the apps of the device`() = runTest {
        val engine = FakeEngine().apply { setPerApp(PerApp(AppPolicy.ExceptListed, setOf(chrome))) }
        val state = collected(engine).state
        assertTrue(state.loaded)
        assertEquals(RvAppPolicy.Except, state.policy)
        assertEquals(setOf(chrome), state.checked)
        assertEquals(sample, state.shown)
        assertEquals(RvSave.Idle, state.save)
        assertFalse("nothing is edited yet", state.canSave)
    }

    @Test
    fun `each of the three policies reaches the engine as its own`() = runTest {
        val cases = listOf(RvAppPolicy.Only to AppPolicy.OnlyListed, RvAppPolicy.Except to AppPolicy.ExceptListed, RvAppPolicy.All to AppPolicy.All)
        for ((policy, engined) in cases) {
            // Start from another policy, so each one is an edit that can be saved.
            val start = if (engined == AppPolicy.All) AppPolicy.ExceptListed else AppPolicy.All
            val engine = FakeEngine().apply { setPerApp(PerApp(start)) }
            val vm = collected(engine)
            vm.onAction(PerAppAction.SetPolicy(policy))
            vm.onAction(PerAppAction.Toggle(chrome, true))
            vm.onAction(PerAppAction.Save)
            assertEquals(PerApp(engined, setOf(chrome)), engine.settings.value.perApp)
            assertEquals("the page shows what was saved", policy, vm.state.saved.policy)
        }
    }

    @Test
    fun `shows the apps as loading and cannot save while they are read`() = runTest {
        val gate = CompletableDeferred<List<InstalledApp>>()
        val vm = collected(
            FakeEngine(),
            apps = object : InstalledApps {
                override suspend fun launchable() = gate.await()
            },
        )
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        assertNull("the skeleton stands for the apps", vm.state.installed)
        assertFalse("the apps decide what can be saved, so wait for them", vm.state.canSave)

        gate.complete(FakeInstalledApps.SAMPLE_APPS)
        assertTrue(vm.state.loaded)
        assertTrue(vm.state.canSave)
    }

    @Test
    fun `a device that cannot list its apps shows the error, not a phone without apps, and stays usable for every policy`() = runTest {
        val engine = FakeEngine()
        val vm = collected(
            engine,
            apps = object : InstalledApps {
                override suspend fun launchable(): List<InstalledApp> = throw IllegalStateException("no package manager")
            },
        )
        assertTrue(vm.state.readFailed)
        assertTrue("nothing to search or check", vm.state.unread)
        assertEquals(emptyList<RvApp>(), vm.state.installed)
        assertEquals(RvSave.Idle, vm.state.save)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Save)
        assertEquals(AppPolicy.ExceptListed, engine.settings.value.perApp.policy)
    }

    @Test
    fun `retrying a failed read keeps the error while it fails and shows the apps once it works`() = runTest {
        val apps = ChangingApps(FakeInstalledApps.SAMPLE_APPS).apply { failure = IllegalStateException("no package manager") }
        val vm = collected(FakeEngine(), apps)
        assertTrue(vm.state.readFailed)

        vm.onAction(PerAppAction.Reload)
        assertEquals(2, apps.reads)
        assertTrue("nothing has been read yet, so the error stays", vm.state.readFailed)

        apps.failure = null
        vm.onAction(PerAppAction.Reload)
        assertEquals(3, apps.reads)
        assertFalse(vm.state.readFailed)
        assertEquals(sample, vm.state.installed)
    }

    @Test
    fun `search matches the label and the package name without regard to case`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))

        vm.onAction(PerAppAction.SetQuery("TELE"))
        assertEquals(listOf(telegram), vm.state.shown.map { it.packageName })
        vm.onAction(PerAppAction.SetQuery("com.google"))
        assertEquals(listOf("YouTube"), vm.state.shown.map { it.label })
        vm.onAction(PerAppAction.SetQuery("計算"))
        assertEquals(listOf("計算機"), vm.state.shown.map { it.label })
        vm.onAction(PerAppAction.SetQuery("  "))
        assertEquals(sample, vm.state.shown)
    }

    @Test
    fun `a search with no result is told apart from a device without apps`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        vm.onAction(PerAppAction.SetQuery("zzz"))
        assertTrue(vm.state.shown.isEmpty())
        assertEquals("the device has apps, the search matched none", sample, vm.state.installed)

        val bare = collected(FakeEngine(), apps = FakeInstalledApps(emptyList()))
        assertEquals(emptyList<RvApp>(), bare.state.installed)
    }

    @Test
    fun `a check survives a search that hides its row`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.SetQuery("telegram"))
        assertEquals(setOf(chrome), vm.state.checked)
    }

    @Test
    fun `toggling edits the draft and only a difference can be saved`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        assertTrue(vm.state.canSave)

        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Toggle(telegram, true))
        assertEquals(setOf(chrome, telegram), vm.state.checked)
        vm.onAction(PerAppAction.Toggle(telegram, false))
        assertEquals(setOf(chrome), vm.state.checked)

        vm.onAction(PerAppAction.Toggle(chrome, false))
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.All))
        assertFalse("back at the saved policy there is nothing to save", vm.state.canSave)
    }

    @Test
    fun `an edit does not reach the engine until it is saved`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        assertEquals(PerApp(), engine.settings.value.perApp)
    }

    @Test
    fun `save hands the edits to the engine in one call and the page follows the engine again`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)

        assertEquals(PerApp(AppPolicy.OnlyListed, setOf(chrome)), engine.settings.value.perApp)
        val state = vm.state
        assertEquals(RvSave.Idle, state.save)
        assertFalse("saved, so nothing is left to save", state.canSave)
        assertNull("no edit is left over the saved policy", state.draft)
        assertEquals(RvAppPolicy.Only, state.policy)
        assertEquals(setOf(chrome), state.checked)

        engine.setPerApp(PerApp(AppPolicy.All))
        assertEquals("an unedited page shows what the engine saves", RvAppPolicy.All, vm.state.policy)
    }

    @Test
    fun `before the stored policy has loaded the apps show as loading and an edit is ignored`() = runTest {
        val engine = FakeEngine().apply { perAppLoaded.value = false }
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Toggle(telegram, true))
        assertNull("the policy shown is a placeholder, so the page waits", vm.state.installed)
        assertFalse(vm.state.canSave)
        vm.onAction(PerAppAction.Save)
        assertEquals("nothing reached the engine", PerApp(), engine.settings.value.perApp)

        // The stored policy arrives late, and is not the default.
        engine.setPerApp(PerApp(AppPolicy.OnlyListed, setOf(chrome)))
        engine.perAppLoaded.value = true
        val state = vm.state
        assertTrue(state.loaded)
        assertEquals(RvAppPolicy.Only, state.policy)
        assertEquals(setOf(chrome), state.checked)
        assertFalse("the ignored edits left no draft", state.canSave)

        vm.onAction(PerAppAction.Toggle(telegram, true))
        vm.onAction(PerAppAction.Save)
        assertEquals(PerApp(AppPolicy.OnlyListed, setOf(chrome, telegram)), engine.settings.value.perApp)
    }

    @Test
    fun `a save that is stored but whose tunnel fails to rebuild shows the rebuild notice, not a failed write`() = runTest {
        val vm = collected(RebuildFails(reason = FailureReason.StartFailed))
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)

        val state = vm.state
        assertTrue(state.rebuildFailed)
        assertEquals("the approved sentence for a tunnel that did not start", R.string.vpn_failure_start_failed, state.rebuildReason)
        assertEquals("the write succeeded", RvSave.Idle, state.save)
        assertFalse("it is saved, so there is nothing left to save", state.canSave)
        assertEquals(RvAppPolicy.Only, state.saved.policy)
    }

    @Test
    fun `the rebuild notice gives 活動's sentence for the reason the tunnel did not come back`() = runTest {
        val vm = collected(RebuildFails(reason = FailureReason.Revoked))
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Save)
        assertEquals(R.string.rv_vpn_taken_reason, vm.state.rebuildReason)
        assertEquals(FailureFix.Retry, vm.state.rebuildFix)
    }

    @Test
    fun `a core this doona does not match is sent to an update, not a retry, and the notice goes`() = runTest {
        val vm = collected(RebuildFails(reason = FailureReason.IncompatibleCore))
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Save)
        assertEquals(R.string.vpn_failure_incompatible_core, vm.state.rebuildReason)
        assertEquals(FailureFix.CheckUpdate, vm.state.rebuildFix)
        vm.onAction(PerAppAction.CheckUpdate)
        assertFalse(vm.state.rebuildFailed)
    }

    @Test
    fun `the system's lockdown is sent to the VPN settings, not a retry, and the notice goes`() = runTest {
        val vm = collected(RebuildFails(reason = FailureReason.LockdownEnabled))
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Save)
        assertEquals(R.string.vpn_failure_lockdown_enabled, vm.state.rebuildReason)
        assertEquals(FailureFix.VpnSettings, vm.state.rebuildFix)
        vm.onAction(PerAppAction.VpnSettings)
        assertFalse(vm.state.rebuildFailed)
    }

    @Test
    fun `a rebuild that found none of the listed apps installed asks for the list, not a retry`() = runTest {
        val vm = collected(RebuildFails(reason = FailureReason.NoAllowedApps))
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Save)

        val state = vm.state
        assertTrue(state.rebuildFailed)
        assertEquals(FailureFix.EditList, state.rebuildFix)
        assertEquals("活動's sentence for the same cause", R.string.vpn_failure_no_allowed_apps, state.rebuildReason)
        vm.onAction(PerAppAction.EditList)
        assertFalse(vm.state.rebuildFailed)
    }

    @Test
    fun `a rebuild that can be tried again offers 重試, and retrying clears the notice`() = runTest {
        val vm = collected(RebuildFails(reason = FailureReason.StartFailed))
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Save)
        assertEquals(FailureFix.Retry, vm.state.rebuildFix)
        vm.onAction(PerAppAction.Reconnect)
        assertFalse(vm.state.rebuildFailed)
    }

    @Test
    fun `a rebuild the core refused for want of nodes is sent to 新增訂閱, not a retry, and the notice goes`() = runTest {
        val vm = collected(RebuildFails(reason = FailureReason.StartFailed, code = ErrorCode.NoNodes))
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Save)
        assertEquals("活動's 沒有節點", R.string.first_no_nodes, vm.state.rebuildReason)
        assertEquals(FailureFix.AddSubscription, vm.state.rebuildFix)
        vm.onAction(PerAppAction.AddSubscription)
        assertFalse(vm.state.rebuildFailed)
    }

    @Test
    fun `a failed write is not a rebuild failure and a later save clears both`() = runTest {
        val engine = FlakyEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Save)
        assertFalse(vm.state.rebuildFailed)
        assertEquals(RvSave.Failed, vm.state.save)

        engine.failing = false
        vm.onAction(PerAppAction.Save)
        assertEquals(RvSave.Idle, vm.state.save)
        assertFalse(vm.state.rebuildFailed)
    }

    @Test
    fun `the apps are read again when the page resumes, so a removed app no longer counts`() = runTest {
        val gone = InstalledApp("com.example.gone", "Gone")
        val apps = ChangingApps(listOf(gone) + FakeInstalledApps.SAMPLE_APPS)
        val vm = collected(FakeEngine(), apps)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        vm.onAction(PerAppAction.Toggle(gone.packageName, true))
        assertTrue("the only checked app is installed", vm.state.canSave)

        // The user uninstalls it while the page waits in the background.
        apps.apps = FakeInstalledApps.SAMPLE_APPS
        vm.onAction(PerAppAction.Resume)

        assertEquals(2, apps.reads)
        assertFalse(vm.state.shown.any { it.packageName == gone.packageName })
        assertTrue("only checked now routes no installed app", vm.state.needsApp)
        assertFalse(vm.state.canSave)
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
        assertTrue(vm.state.loaded)
    }

    @Test
    fun `a resume during a read is read again once it ends, so the page shows the apps the device has now`() = runTest {
        val gone = InstalledApp("com.example.gone", "Gone")
        var device = listOf(gone) + FakeInstalledApps.SAMPLE_APPS
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val vm = collected(
            FakeEngine(),
            object : InstalledApps {
                override suspend fun launchable(): List<InstalledApp> {
                    reads++
                    val seen = device
                    // Only the first read is slow: it has looked at the device before the app goes away.
                    if (reads == 1) gate.await()
                    return seen
                }
            },
        )
        device = FakeInstalledApps.SAMPLE_APPS
        vm.onAction(PerAppAction.Resume)
        assertEquals("the resume does not start a second read beside the first", 1, reads)

        gate.complete(Unit)

        assertEquals("the resume's read ran when the first ended", 2, reads)
        assertEquals("the page does not keep the app the first read saw", sample, vm.state.installed)
    }

    @Test
    fun `many resumes during a read cost one more read`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val vm = collected(
            FakeEngine(),
            object : InstalledApps {
                override suspend fun launchable(): List<InstalledApp> {
                    if (++reads == 1) gate.await()
                    return FakeInstalledApps.SAMPLE_APPS
                }
            },
        )
        repeat(3) { vm.onAction(PerAppAction.Resume) }
        gate.complete(Unit)

        assertEquals(2, reads)
    }

    @Test
    fun `a read that fails on resume keeps the apps the page had`() = runTest {
        val apps = ChangingApps(FakeInstalledApps.SAMPLE_APPS)
        val vm = collected(FakeEngine(), apps)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        apps.failure = IllegalStateException("no package manager")
        vm.onAction(PerAppAction.Resume)

        assertEquals(2, apps.reads)
        assertEquals(sample, vm.state.installed)
        assertFalse("the page already has a list, so a failed refresh is not an error", vm.state.readFailed)
        assertTrue(vm.state.canSave)
    }

    @Test
    fun `a failed save keeps the edits and can be tried again`() = runTest {
        val engine = FlakyEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)

        var state = vm.state
        assertEquals(RvSave.Failed, state.save)
        assertTrue("the edits are still there to save again", state.canSave)
        assertEquals(setOf(chrome), state.checked)
        assertEquals(PerApp(), engine.settings.value.perApp)

        engine.failing = false
        vm.onAction(PerAppAction.Save)
        state = vm.state
        assertEquals(RvSave.Idle, state.save)
        assertEquals(PerApp(AppPolicy.OnlyListed, setOf(chrome)), engine.settings.value.perApp)
    }

    @Test
    fun `a failed save goes once the edits come back to what is saved`() = runTest {
        val engine = FlakyEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        vm.onAction(PerAppAction.Save)
        assertEquals(RvSave.Failed, vm.state.save)

        vm.onAction(PerAppAction.SetPolicy(engine.settings.value.perApp.policy.toRv()))

        assertEquals("nothing is left to write, so no notice offers 重試", RvSave.Idle, vm.state.save)
        assertNull(vm.state.draft)
        assertFalse(vm.state.canSave)
    }

    @Test
    fun `a save in progress takes no second save and keeps an edit made meanwhile`() = runTest {
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
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)
        assertEquals(RvSave.Saving, vm.state.save)
        assertFalse(vm.state.canSave)

        vm.onAction(PerAppAction.Save)
        vm.onAction(PerAppAction.Toggle(telegram, true))
        release.complete(Unit)

        assertEquals("the second save did not reach the engine", 1, calls)
        assertEquals(PerApp(AppPolicy.OnlyListed, setOf(chrome)), engine.settings.value.perApp)
        val state = vm.state
        assertEquals(RvSave.Idle, state.save)
        assertEquals("the edit made during the save stays", setOf(chrome, telegram), state.checked)
        assertTrue(state.canSave)
    }

    @Test
    fun `only checked on a phone without apps cannot be saved and does not ask for an app`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine, FakeInstalledApps(emptyList()))
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        assertTrue(vm.state.needsApp)
        assertFalse("there is no app to check", vm.state.asksForApp)
        assertFalse(vm.state.canSave)
    }

    @Test
    fun `only checked with nothing checked cannot be saved and says why`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        assertTrue(vm.state.needsApp)
        assertTrue("the phone lists apps, so the page asks for one", vm.state.asksForApp)
        assertFalse(vm.state.canSave)

        vm.onAction(PerAppAction.Save)
        assertEquals("nothing reached the engine", PerApp(), engine.settings.value.perApp)
        assertEquals(RvSave.Idle, vm.state.save)

        vm.onAction(PerAppAction.Toggle(chrome, true))
        assertFalse(vm.state.needsApp)
        assertTrue(vm.state.canSave)
        vm.onAction(PerAppAction.Toggle(chrome, false))
        assertTrue(vm.state.needsApp)
    }

    @Test
    fun `only apps that are installed count as checked for only checked`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Only))
        vm.onAction(PerAppAction.Toggle("com.removed.app", true))
        assertTrue("the tunnel would route no app", vm.state.needsApp)
        assertFalse(vm.state.canSave)
        assertEquals("the stored package stays in the set", setOf("com.removed.app"), vm.state.checked)
    }

    @Test
    fun `except checked with nothing checked is a policy that can be saved`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        vm.onAction(PerAppAction.SetPolicy(RvAppPolicy.Except))
        assertFalse(vm.state.needsApp)
        vm.onAction(PerAppAction.Save)
        assertEquals(PerApp(AppPolicy.ExceptListed), engine.settings.value.perApp)
    }

    @Test
    fun `packages of apps that are gone stay in the saved set, and so in the count 設定 shows`() = runTest {
        val engine = FakeEngine().apply { setPerApp(PerApp(AppPolicy.ExceptListed, setOf("com.removed.app"))) }
        val vm = collected(engine)
        vm.onAction(PerAppAction.Toggle(chrome, true))
        vm.onAction(PerAppAction.Save)
        assertEquals(setOf("com.removed.app", chrome), engine.settings.value.perApp.packages)
        assertEquals(2, vm.state.saved.packages.size)
    }
}
