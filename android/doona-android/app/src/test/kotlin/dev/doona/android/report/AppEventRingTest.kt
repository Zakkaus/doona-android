package dev.doona.android.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppEventRingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = tmp.root.resolve("app-events.tsv")

    @After
    fun tearDown() = AppEvents.clear()

    @Test
    fun restartsWithSavedEventsBeforeNewRecords() = runTest {
        install()
        val first = AppEvents.record(AppEventLevel.Warn, TIMEOUT, atEpochMs = 20)
        runCurrent()

        install()
        val second = AppEvents.record(AppEventLevel.Info, ACTION, atEpochMs = 10)
        assertEquals(listOf(first, second), AppEvents.reportSnapshot())
    }

    @Test
    fun mergeKeepsTheNewestSavedRowsThatFitBesideStartupRecords() = runTest {
        checkMerge(newCount = 150)
    }

    @Test
    fun aRingAlreadyFullOfStartupRecordsKeepsNoneFromDisk() = runTest {
        checkMerge(newCount = AppEvents.CAPACITY)
    }

    private suspend fun TestScope.checkMerge(newCount: Int) {
        val capacity = AppEvents.CAPACITY
        install()
        repeat(capacity) { AppEvents.record(AppEventLevel.Info, ACTION, atEpochMs = it.toLong()) }
        runCurrent()

        install()
        repeat(newCount) { AppEvents.record(AppEventLevel.Warn, TIMEOUT, atEpochMs = capacity.toLong() + it) }
        val merged = AppEvents.reportSnapshot()
        assertEquals((newCount.toLong() until capacity + newCount.toLong()).toList(), merged.map { it.atEpochMs })
    }

    @Test
    fun crashBreadcrumbsExcludeReloadedActionsAndKeepEarlyActions() = runTest {
        AppEvents.clear()
        val saved = AppEvents.record(AppEventLevel.Info, ACTION, atEpochMs = 1)
        install()
        runCurrent()
        AppEvents.clear()
        val startup = AppEvents.record(AppEventLevel.Info, ACTION, atEpochMs = 2)
        install()

        assertEquals(listOf(saved, startup), AppEvents.reportSnapshot())
        assertEquals(listOf(startup), AppEvents.breadcrumbs(50))
        repeat(AppEvents.CAPACITY - 1) { AppEvents.record(AppEventLevel.Warn, TIMEOUT) }
        assertEquals(listOf(startup), AppEvents.breadcrumbs(50))
    }

    @Test
    fun replacingTheRingReleasesAReportWaitingForItsLoad() = runTest {
        install()
        val current = AppEvents.record(AppEventLevel.Warn, TIMEOUT)
        val report = async(start = CoroutineStart.UNDISPATCHED) { AppEvents.reportSnapshot() }
        AppEvents.clear()

        assertEquals(listOf(current), report.await())
    }

    @Test
    fun eachPublishedFileKeepsOnlyTheNewestEvents() = runTest {
        install()
        val capacity = AppEvents.CAPACITY
        repeat(3) { batch ->
            repeat(capacity + 1) { offset ->
                AppEvents.record(AppEventLevel.Info, ACTION, atEpochMs = batch.toLong() * (capacity + 1) + offset)
            }
            runCurrent()
            val expected = (batch + 1L) * (capacity + 1) - capacity until (batch + 1L) * (capacity + 1)
            assertEquals(expected.toList(), file.readLines().map { it.substringBefore('\t').toLong() })
        }
        install()
        assertEquals((2L * capacity + 3 until 3L * capacity + 3).toList(), AppEvents.reportSnapshot().map { it.atEpochMs })
    }

    @Test
    fun skipsAnUnterminatedLastRowEvenWhenItLooksComplete() = runTest {
        install()
        val saved = AppEvents.record(AppEventLevel.Warn, TIMEOUT, atEpochMs = 1)
        runCurrent()
        file.appendText("2\tInfo\t$ACTION")

        install()
        assertEquals(listOf(saved), AppEvents.reportSnapshot())
    }

    @Test
    fun skipsCorruptRowsBetweenCompleteEvents() = runTest {
        file.writeText("1\tWarn\t$TIMEOUT\n2\tUnknown\t$ACTION\n3\tInfo\t\u0000\n4\tInfo\t$ACTION\n")
        install()

        assertEquals(listOf(AppEvent(1, AppEventLevel.Warn, TIMEOUT), AppEvent(4, AppEventLevel.Info, ACTION)), AppEvents.reportSnapshot())
    }

    @Test
    fun failedReplacementPreservesTheTargetAndMemoryAndRemovesThePart() = runTest {
        assertTrue(file.mkdir())
        val original = file.resolve("original").apply { writeText("untouched") }
        install()
        val event = AppEvents.record(AppEventLevel.Warn, TIMEOUT)
        runCurrent()

        assertEquals(listOf(event), AppEvents.reportSnapshot())
        assertEquals("untouched", original.readText())
        assertFalse(tmp.root.resolve("app-events.tsv.part").exists())
    }

    @Test
    fun keepsIdentifiersInMemoryAndScrubsUnknownTextBeforeWriting() = runTest {
        install()
        val event = AppEvents.record(AppEventLevel.Info, ACTION, visitedDomain = "visited.example", appPackage = "dev.secret.app")
        val unsafe = AppEvents.record(AppEventLevel.Warn, "failed: https://private.example/sub?token=secret")
        runCurrent()
        assertEquals(listOf(event, unsafe), AppEvents.snapshot())
        val onDisk = file.readText()
        for (secret in listOf("visited.example", "dev.secret.app", "private.example", "token=secret")) {
            assertFalse(onDisk, secret in onDisk)
        }

        install()
        assertEquals(
            listOf(event.copy(visitedDomain = null, appPackage = null), unsafe.copy(text = REDACTED)),
            AppEvents.reportSnapshot(),
        )
    }

    @Test
    fun recordReturnsBeforeTheWriterAccessesTheFile() = runTest {
        var resolved = false
        AppEvents.install(StandardTestDispatcher(testScheduler)) {
            resolved = true
            file
        }
        val first = AppEvents.record(AppEventLevel.Warn, TIMEOUT, atEpochMs = 1)
        assertFalse(resolved)
        assertFalse(file.exists())
        assertEquals(listOf(first), AppEvents.snapshot())

        runCurrent()
        assertTrue(resolved)
        assertEquals("1\tWarn\t$TIMEOUT\n", file.readText())
    }

    private fun TestScope.install() = AppEvents.install(StandardTestDispatcher(testScheduler)) { file }

    private companion object {
        const val ACTION = "command=updateSubscription phase=complete outcome=ok code=ok"
        const val TIMEOUT = "subscription-refresh: core readiness timed out"
    }
}
