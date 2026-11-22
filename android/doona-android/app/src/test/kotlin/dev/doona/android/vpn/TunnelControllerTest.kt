package dev.doona.android.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real threads, as on the phone: the core blocks the controller's thread while the test acts from another. */
@RunWith(AndroidJUnit4::class)
class TunnelControllerTest {
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
        opener = CoreOpener(core, "/data", Dispatchers.IO),
        perApp = { perAppError?.let { throw it } ?: perApp },
        scope = scope,
        killProcess = { killed.countDown() },
        startBoundMs = 300,
        stopDeadlineMs = 200,
    )
    private val seen = CopyOnWriteArrayList<Status>()

    @Before
    fun launch() {
        scope.launch(Dispatchers.IO.limitedParallelism(1)) { controller.run() }
        scope.launch(Dispatchers.Unconfined) { controller.status.collect { seen += it } }
    }

    @After
    fun tearDown() = scope.cancel()

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
    }

    @Test
    fun revokeDuringABlockedStartStopsTheLateSuccess() {
        val release = CountDownLatch(1)
        core.startGate = release
        start()
        assertTrue(core.startEntered.await(5, TimeUnit.SECONDS))

        controller.stop()
        assertEquals(Status.Stopping, controller.status.value)
        release.countDown()

        awaitStatus { it == Status.Stopped }
        assertEquals(listOf("start", "stop"), core.events)
        assertTrue(host.tuns.single().closed)
        assertFalse(seen.any { it is Status.Running })
    }

    @Test
    fun startFailureIsReportedOnceAndClosesTheTun() {
        core.startError = IllegalStateException("internal")
        start()
        assertEquals(Status.Failed(FailureReason.StartFailed), awaitStatus { it is Status.Failed })
        assertEquals(listOf("start"), core.events)
        assertTrue(host.tuns.single().closed)
        assertFalse(seen.any { it is Status.Running })
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
        core.stopError = IllegalStateException("internal")
        controller.stop()
        assertTrue(killed.await(5, TimeUnit.SECONDS))
        assertEquals(Status.Stopping, controller.status.value)
        assertFalse(seen.dropWhile { it !is Status.Running }.contains(Status.Stopped))
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
        assertEquals(Status.Starting, controller.status.value)
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
        core.schema = 2
        start()
        assertEquals(Status.Failed(FailureReason.IncompatibleCore), awaitStatus { it is Status.Failed })
        assertTrue(host.routings.isEmpty())
    }

    @Test
    fun aDeadDataPathFailsTheSessionItWasSeenIn() {
        val running = startRunning()
        controller.coreStopped(Status.Running(running.sinceEpochMs - 1))
        assertEquals(running, controller.status.value)

        controller.coreStopped(running)
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
            scope.launch(main) { session.follow { ended += it } }
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
        @Volatile var schema = CORE_SCHEMA

        @Volatile var startError: Throwable? = null

        @Volatile var startGate: CountDownLatch? = null

        @Volatile var stopGate: CountDownLatch? = null

        @Volatile var stopError: Throwable? = null
        val startEntered = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val configs = CopyOnWriteArrayList<String>()

        override fun init(dataDir: String) = """{"schema":$schema}"""

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

        override fun call(command: String, argsJson: String) = error("unused")
    }

    private class FakeTun(override val fd: Int) : Tun {
        @Volatile var closed = false

        override fun close() {
            closed = true
        }
    }

    private class FakeHost : TunHost {
        override val selfPackage = SELF
        val routings = CopyOnWriteArrayList<AppRouting>()
        val tuns = CopyOnWriteArrayList<FakeTun>()

        override fun installed(packages: Set<String>) = packages intersect setOf(FIREFOX)

        override fun bootstrapDns() = listOf("192.168.8.1")

        override fun establish(routing: AppRouting): Tun {
            routings += routing
            return FakeTun(fd = 40 + tuns.size).also { tuns += it }
        }
    }

    private companion object {
        const val SELF = "dev.doona.android"
        const val FIREFOX = "org.mozilla.firefox"
    }
}
