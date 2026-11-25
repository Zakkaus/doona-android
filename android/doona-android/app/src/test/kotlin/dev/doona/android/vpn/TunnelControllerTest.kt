package dev.doona.android.vpn

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.geo.GeoAssets
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import dev.doona.android.report.AppEventLevel
import dev.doona.android.report.AppEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real threads, as on the phone: the core blocks the controller's thread while the test acts from another. */
@RunWith(AndroidJUnit4::class)
// Keep native-core startup in DoonaApplication from adding unrelated entries to AppEvents.
@Config(application = Application::class)
class TunnelControllerTest {
    private val validPins = """{"release_date":"2026-11-23"}"""

    @get:Rule
    val folder = TemporaryFolder()

    private var pins: String? = null
    private var dat: (String) -> String = { it }
    private val geoAssets = GeoAssets({ folder.root }) { path ->
        val contents = if (path == "geo/pins.json") pins ?: throw FileNotFoundException(path) else dat(path)
        contents.byteInputStream()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val core = FakeCore()
    private val host = FakeHost()

    @Volatile
    private var perApp = PerApp()

    @Volatile
    private var perAppError: IOException? = null
    private val killed = CountDownLatch(1)
    private val controller = TunnelController(
        core = core,
        opener = CoreOpener(core, { "/data" }, Dispatchers.IO),
        perApp = { perAppError?.let { throw it } ?: perApp },
        scope = scope,
        killProcess = { killed.countDown() },
        endDebugCapture = {},
        expireDebugCapture = { null },
        startBoundMs = 300,
        stopDeadlineMs = 200,
        geoAssets = geoAssets,
    )
    private lateinit var lane: Job
    private val seen = CopyOnWriteArrayList<Status>()

    @Before
    fun launch() {
        lane = scope.launch(Dispatchers.IO.limitedParallelism(1)) { controller.run() }
        scope.launch(Dispatchers.Unconfined) { controller.status.collect { seen += it } }
    }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        withTimeout(5_000) { lane.join() }
    }

    private fun awaitStatus(predicate: (Status) -> Boolean): Status =
        runBlocking { withTimeout(5_000) { controller.status.first(predicate) } }

    private fun start() = assertTrue(controller.start(host, checkNotNull(controller.requestStart())))

    private fun startRunning(): Status.Running {
        start()
        return awaitStatus { it is Status.Running } as Status.Running
    }

    @Test
    fun startLendsTheTunAndPublishesRunning() {
        startRunning()
        assertEquals(listOf("start"), core.events)
        assertEquals(listOf(AppRouting.Exclude(setOf(SELF))), host.routings)
        assertTrue(host.tuns.single().closed)
        assertTrue("bootstrap DNS read before establish", core.configs.single().contains("192.168.8.1"))
        assertFalse("geoAssetDir" in core.configs.single())
    }

    @Test
    fun startHandsAnExtractedBundleToTheCoreAndThenRemovesIt() {
        pins = validPins
        val stage = folder.root.resolve("geo-apk-stage")
        val release = CountDownLatch(1)
        core.startGate = release
        start()
        try {
            assertTrue(core.startEntered.await(5, TimeUnit.SECONDS))
            assertEquals("geo/geoip.dat", stage.resolve("geoip.dat").readText())
        } finally {
            release.countDown()
        }
        awaitStatus { it is Status.Running }
        assertTrue(core.configs.single().contains("\"geoAssetDir\":\"${stage.path}\""))
        assertFalse(stage.exists())
    }

    @Test
    fun geoStatusErrorDoesNotBlockVpnStart() {
        pins = validPins
        core.geoStatus = """{"error":{"code":"internal","message":"private path"}}"""
        assertStartsWithoutGeo("EngineException")
    }

    @Test
    fun malformedGeoPinsDoNotBlockVpnStart() {
        pins = "{}"
        assertStartsWithoutGeo("MissingFieldException")
    }

    @Test
    fun invalidGeoDatesDoNotBlockVpnStart() {
        pins = validPins
        core.geoStatus = """{"ok":{"source":"apk","release":"2026-02-30","lastResult":"idle","reason":null,"updateWaitingForNextStart":false}}"""
        assertStartsWithoutGeo("DateTimeParseException")
    }

    @Test
    fun stagingWriteFailureDoesNotBlockVpnStart() {
        pins = validPins
        val full = Path.of("/dev/full")
        val hasFullDevice = Files.isWritable(full)
        dat = { path ->
            if (path == "geo/geosite.dat") {
                val target = folder.root.resolve("geo-apk-stage/geosite.dat")
                if (hasFullDevice) Files.createSymbolicLink(target.toPath(), full) else assertTrue(target.mkdir())
            }
            path
        }
        assertStartsWithoutGeo(if (hasFullDevice) "IOException" else "FileNotFoundException")
    }

    private fun assertStartsWithoutGeo(exception: String) {
        AppEvents.clear()
        start()
        assertTrue(awaitStatus { it is Status.Running || it is Status.Failed } is Status.Running)
        assertFalse("geoAssetDir" in core.configs.single())
        assertTrue(folder.root.listFiles()!!.isEmpty())
        val warnings = AppEvents.snapshot().filter { it.level == AppEventLevel.Warn && it.text.startsWith("geo-assets:") }
        assertEquals(listOf("geo-assets: APK offer failed ($exception)"), warnings.map { it.text })
    }

    @Test
    fun cancellingWhileNativeStartReturnsSuccessStopsTheCore() = runBlocking {
        pins = validPins
        val release = CountDownLatch(1)
        core.startGate = release
        start()
        try {
            assertTrue(core.startEntered.await(5, TimeUnit.SECONDS))
            lane.cancel()
        } finally {
            release.countDown()
        }
        withTimeout(5_000) { lane.join() }
        assertEquals(listOf("start", "stop"), core.events)
        assertTrue(host.tuns.single().closed)
        assertFalse(folder.root.resolve("geo-apk-stage").exists())
    }

    @Test
    fun theTunAndTheCoreTakeTheHostsMtu() {
        host.mtu = 1400
        startRunning()
        assertEquals(listOf(1400), host.mtus)
        assertTrue(core.configs.single(), core.configs.single().contains("\"mtu\":1400"))
    }

    @Test
    fun revokeDuringABlockedStartStopsTheLateSuccess() {
        val release = CountDownLatch(1)
        core.startGate = release
        start()
        assertTrue(core.startEntered.await(5, TimeUnit.SECONDS))

        controller.revoke(host)
        assertTrue(Status.Stopping in seen)
        release.countDown()

        assertEquals(Status.Failed(FailureReason.Revoked), awaitStatus(Status::ended))
        assertEquals(listOf("start", "stop"), core.events)
        assertTrue(host.tuns.single().closed)
        assertFalse(seen.any { it is Status.Running })
    }

    @Test
    fun revocationFailsARunningTunnelButLeavesAnExplicitStopStopped() {
        startRunning()
        controller.revoke(host)
        assertEquals(Status.Failed(FailureReason.Revoked), awaitStatus(Status::ended))

        startRunning()
        controller.stop()
        controller.revoke(host)
        assertEquals(Status.Stopped, awaitStatus(Status::ended))
    }

    @Test
    fun revokeFromAnotherHostLeavesTheOwnersTunnelRunning() {
        val running = startRunning()
        controller.revoke(FakeHost())
        assertEquals(running, controller.status.value)
    }

    @Test
    fun startFailureIsReportedOnceAndClosesTheTun() {
        pins = validPins
        core.startError = IllegalStateException("internal")
        start()
        assertEquals(Status.Failed(FailureReason.StartFailed, ErrorCode.Internal), awaitStatus { it is Status.Failed })
        assertEquals(listOf("start"), core.events)
        assertTrue(host.tuns.single().closed)
        val stage = folder.root.resolve("geo-apk-stage")
        assertTrue(stage.resolve("geosite.dat").isFile)
        assertFalse(seen.any { it is Status.Running })

        pins = null
        core.startError = null
        startRunning()
        assertFalse(stage.exists())
        assertFalse("geoAssetDir" in core.configs.last())
    }

    @Test
    fun theCoresOwnErrorCodeSurvivesTheStartFailure() {
        core.startError = IllegalStateException("no_nodes")
        start()
        assertEquals(Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes), awaitStatus { it is Status.Failed })
        assertTrue(host.tuns.single().closed)
    }

    @Test
    fun anAndroidFailureWhileBuildingTheTunCarriesNoCoreCode() {
        // The message is a core code, but it comes from the system, not from the core.
        host.establishError = IllegalStateException("no_nodes")
        start()
        assertEquals(Status.Failed(FailureReason.StartFailed), awaitStatus { it is Status.Failed })
        assertTrue(core.events.isEmpty())
    }

    @Test
    fun aCodeFromANewerCoreIsInternal() {
        core.startError = IllegalStateException("from_the_future")
        start()
        assertEquals(Status.Failed(FailureReason.StartFailed, ErrorCode.Internal), awaitStatus { it is Status.Failed })
    }

    @Test
    fun anExceptionThatIsNotACoreCodeCarriesNone() {
        core.startError = IOException("socket")
        start()
        assertEquals(Status.Failed(FailureReason.StartFailed), awaitStatus { it is Status.Failed })
    }

    @Test
    fun aMissingLibraryIsAStartFailure() {
        core.startError = UnsatisfiedLinkError("start")
        start()
        assertEquals(Status.Failed(FailureReason.StartFailed), awaitStatus { it is Status.Failed })
        assertTrue(host.tuns.single().closed)
    }

    @Test
    fun aStopPastTheDeadlineEndsTheProcess() {
        startRunning()
        val release = CountDownLatch(1)
        core.stopGate = release
        controller.stop()
        assertTrue(killed.await(5, TimeUnit.SECONDS))
        release.countDown()
    }

    @Test
    fun aPromptStopDoesNotEndTheProcess() {
        startRunning()
        controller.stop()
        awaitStatus { it == Status.Stopped }
        // Past both the stop deadline and the start bound plus the stop deadline.
        assertFalse(killed.await(700, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aStopThatEndsAsItsDeadlinePassesDoesNotEndTheProcess() {
        startRunning()
        // Runs inside the publication of Stopped and holds the runner there past the start bound plus the stop deadline.
        scope.launch(Dispatchers.Unconfined) {
            controller.status.first { it == Status.Stopped }
            Thread.sleep(900)
        }
        controller.stop()
        awaitStatus { it == Status.Stopped }
        assertFalse(killed.await(1_000, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aStopDuringAStartThatNeverReturnsEndsTheProcess() {
        val release = CountDownLatch(1)
        core.startGate = release
        start()
        assertTrue(core.startEntered.await(5, TimeUnit.SECONDS))
        controller.stop()
        assertTrue(killed.await(5, TimeUnit.SECONDS))
        release.countDown()
    }

    @Test
    fun aThrowingStopEndsTheProcessInsteadOfReportingStopped() {
        startRunning()
        AppEvents.clear()
        core.stopError = IllegalStateException("internal")
        controller.stop()
        assertTrue(killed.await(5, TimeUnit.SECONDS))
        assertEquals(Status.Stopping, controller.status.value)
        assertFalse(seen.dropWhile { it !is Status.Running }.contains(Status.Stopped))
        val entries = AppEvents.snapshot().map { it.text }.filter { it.startsWith("command=stop") || it.startsWith("stop ") }
        assertEquals(listOf("command=stop phase=complete outcome=failed code=Internal"), entries)
    }

    @Test
    fun onlyListedWithNoInstalledAppIsRefused() {
        perApp = PerApp(AppPolicy.OnlyListed, setOf("gone.app"))
        start()
        assertEquals(Status.Failed(FailureReason.NoAllowedApps), awaitStatus { it is Status.Failed })
        assertTrue(host.routings.isEmpty())
        assertTrue(core.events.isEmpty())
    }

    @Test
    fun rebuildEstablishesANewTunWithTheNewPolicy() {
        startRunning()
        perApp = PerApp(AppPolicy.ExceptListed, setOf(FIREFOX))
        controller.rebuild()
        assertTrue(Status.Starting in seen.dropWhile { it !is Status.Running })
        awaitStatus { it is Status.Running }
        assertEquals(listOf("start", "stop", "start"), core.events)
        assertEquals(AppRouting.Exclude(setOf(FIREFOX, SELF)), host.routings.last())
        assertTrue(host.tuns.all { it.closed })
    }

    @Test
    fun rebuildToAnEmptyAllowListFailsInsteadOfCapturingEveryApp() {
        startRunning()
        perApp = PerApp(AppPolicy.OnlyListed, setOf("gone.app"))
        controller.rebuild()
        assertEquals(Status.Failed(FailureReason.NoAllowedApps), awaitStatus { it is Status.Failed })
        assertEquals(listOf("start", "stop"), core.events)
        assertEquals(1, host.routings.size)
    }

    @Test
    fun anUnreadablePolicyFailsTheStartBeforeTheTunIsBuilt() {
        perAppError = IOException("corrupt")
        start()
        assertEquals(Status.Failed(FailureReason.StartFailed), awaitStatus { it is Status.Failed })
        assertTrue(host.routings.isEmpty())
        assertTrue(core.events.isEmpty())
    }

    @Test
    fun anotherSchemaIsAnIncompatibleCore() {
        core.schema = CORE_SCHEMA + 1
        start()
        assertEquals(Status.Failed(FailureReason.IncompatibleCore), awaitStatus { it is Status.Failed })
        assertTrue(host.routings.isEmpty())
    }

    @Test
    fun aCoreFoundUnusableLateLeavesTheSessionsStatus() {
        // The session's generation already ended with Stopped; a second terminal status would end it twice.
        startRunning()
        controller.stop()
        awaitStatus { it == Status.Stopped }
        controller.unavailable(FailureReason.StartFailed)
        assertEquals(Status.Stopped, controller.status.value)
    }

    @Test
    fun aDeadDataPathFailsTheSessionItWasSeenIn() {
        val running = startRunning()
        val session = runBlocking { withTimeout(5_000) { controller.session.first { it.status == running } } }
        controller.coreStopped(session.copy(generation = session.generation - 1))
        assertEquals(running, controller.status.value)

        controller.coreStopped(session)
        assertEquals(Status.Failed(FailureReason.CoreStopped), awaitStatus { it is Status.Failed })
        assertEquals(listOf("start", "stop"), core.events)
    }

    @Test
    fun aSecondStartWhileStartingIsIgnored() {
        val release = CountDownLatch(1)
        core.startGate = release
        val requested = checkNotNull(controller.requestStart())
        assertTrue(controller.start(host, requested))
        assertNull(controller.requestStart())
        assertFalse(controller.start(host, requested))
        release.countDown()
        awaitStatus { it is Status.Running }
        assertEquals(1, host.routings.size)
    }

    @Test
    fun aStartThatArrivesAfterADisconnectIsIgnored() {
        val requested = checkNotNull(controller.requestStart())
        assertEquals(Status.Starting, controller.status.value)
        controller.stop()
        assertFalse(controller.start(host, requested))
        awaitStatus { it == Status.Stopped }
        assertTrue(host.routings.isEmpty())
    }

    @Test
    fun aStaleServiceGoingAwayLeavesTheNextStart() {
        // A start cancelled before its intent arrives; its service stops itself, and the user connects again before
        // Android destroys that service.
        val stale = checkNotNull(controller.requestStart())
        controller.stop()
        awaitStatus { it == Status.Stopped }
        val staleHost = FakeHost()
        assertFalse(controller.start(staleHost, stale))
        val next = checkNotNull(controller.requestStart())
        controller.release(staleHost)
        assertEquals(Status.Starting, controller.status.value)
        assertTrue(controller.start(host, next))
        awaitStatus { it is Status.Running }
    }

    @Test
    fun theServiceGoingAwayStopsTheSessionItTook() {
        startRunning()
        controller.release(host)
        awaitStatus { it == Status.Stopped }
        assertEquals(listOf("start", "stop"), core.events)
    }

    @Test
    fun abandoningAStartLeavesANewerOneAndOneTheServiceTook() {
        val abandoned = checkNotNull(controller.requestStart())
        controller.stop()
        val next = checkNotNull(controller.requestStart())
        controller.abandon(abandoned)
        assertEquals(Status.Starting, controller.status.value)
        assertTrue(controller.start(host, next))
        controller.abandon(next)
        awaitStatus { it is Status.Running }
    }

    @Test
    fun aRebuildBeforeTheServiceTakesTheStartLeavesTheRequest() {
        val requested = checkNotNull(controller.requestStart())
        controller.rebuild()
        assertTrue(controller.start(host, requested))
        awaitStatus { it is Status.Running }
    }

    @Test
    fun theServiceEndsEachSessionItStartedThoughItLooksLate() {
        perApp = PerApp(AppPolicy.OnlyListed, setOf("gone.app"))
        val session = ServiceSession(controller)
        val ended = CopyOnWriteArrayList<Status>()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            scope.launch(main) { session.follow { ended += it.status } }
            repeat(2) { round ->
                // The start fails while the main thread is still busy, so the service never sees Starting; the second
                // failure also equals the first.
                runBlocking(main) {
                    session.start(host, checkNotNull(controller.requestStart()))
                    awaitStatus { it is Status.Failed }
                }
                runBlocking { withTimeout(5_000) { while (ended.size <= round) delay(10) } }
            }
        }
        assertEquals(List(2) { Status.Failed(FailureReason.NoAllowedApps) }, ended)
    }

    private class FakeCore : CoreBridge {
        @Volatile var geoStatus = ContractFixtures.read("call-ok-config-assets.json")

        @Volatile var schema = CORE_SCHEMA

        @Volatile var startError: Throwable? = null

        @Volatile var startGate: CountDownLatch? = null

        @Volatile var stopGate: CountDownLatch? = null

        @Volatile var stopError: Throwable? = null
        val startEntered = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val configs = CopyOnWriteArrayList<String>()

        override fun init(dataDir: String, optionsJson: String) = """{"schema":$schema}"""

        override fun start(tunFd: Int, configJson: String) {
            startEntered.countDown()
            startGate?.await()
            events += "start"
            configs += configJson
            startError?.let { throw it }
        }

        override fun stop() {
            stopGate?.await()
            events += "stop"
            stopError?.let { throw it }
        }

        override fun snapshot(sinceRevision: Long) = error("unused")

        override fun call(command: String, argsJson: String): String {
            check(command == "configAssets")
            check(argsJson == """{"action":"status"}""")
            return geoStatus
        }
    }

    private class FakeTun(override val fd: Int) : Tun {
        @Volatile var closed = false

        override fun close() {
            closed = true
        }
    }

    private class FakeHost : TunHost {
        override val selfPackage = SELF
        override var mtu = StartConfigDto.MTU
        val routings = CopyOnWriteArrayList<AppRouting>()
        val mtus = CopyOnWriteArrayList<Int>()
        val tuns = CopyOnWriteArrayList<FakeTun>()

        /** What the system's `Builder.establish()` throws, if anything. */
        @Volatile var establishError: Throwable? = null

        override fun installed(packages: Set<String>) = packages intersect setOf(FIREFOX)

        override fun bootstrapDns() = listOf("192.168.8.1")

        override fun establish(routing: AppRouting, mtu: Int): Tun {
            establishError?.let { throw it }
            routings += routing
            mtus += mtu
            return FakeTun(fd = 40 + tuns.size).also { tuns += it }
        }
    }

    private companion object {
        const val SELF = "dev.doona.android"
        const val FIREFOX = "org.mozilla.firefox"
    }

    @Test
    fun aRefusalIsLoggedByItsCodeAlone() {
        AppEvents.clear()
        core.startError = IllegalStateException("fetch https://sub.example.net/api?token=tk_5ecret failed")
        start()
        awaitStatus { it is Status.Failed }
        val entries = AppEvents.snapshot().map { it.text.substringBefore(" elapsedMs=") }.filter { it.startsWith("command=") }
        assertEquals(listOf("command=start phase=complete outcome=failed code=Internal"), entries)
    }
}
