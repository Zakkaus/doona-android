package dev.doona.android.report

import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Member
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * The export: a zip that holds no URL, address or host of the catalog it was made from, and a clear after the share that
 * takes only the crash files the zip holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val crashDir get() = tmp.root.resolve("crash")
    private val outDir get() = tmp.root.resolve("reports")

    @Before
    fun setUp() = AppEvents.clear()

    /** What a provider's subscription and a user's catalog can hold: a token URL, servers by address and by name. */
    private val token = "tk_9f8e7d6c5b4a"
    private val secrets = listOf(token, "provider-example.net", "203.0.113.45", "198.51.100.7", "hk1.edge-example.org", "2001:db8::17", "harbor-home", "user:pass")

    private suspend fun fixtureEngine(): FakeEngine {
        val nodes = listOf(
            Member("n-1a2b3c4d5e6f7081", "203.0.113.45:443 hk", latencyMs = 42, subscriptionId = "s1"),
            Member("n-9f8e7d6c5b4a3921", "hk1.edge-example.org vmess", failed = true, error = ErrorCode.LocalNetwork, subscriptionId = "s1"),
            Member("n-0011223344556677", "[2001:db8::17]:8443 user:pass", subscriptionId = "s1"),
        )
        val groups = listOf(
            // A subscription may name its groups too, so a group's name or id is no safer than a node's.
            Group("g-198.51.100.7", "198.51.100.7", GroupKind.Selector, nodes + Member("g-Auto", "Auto", isGroup = true), selectedId = "g-Auto"),
            Group("g-Auto", "Auto", GroupKind.UrlTest, nodes, selectedId = nodes[0].id),
        )
        val subscription = Subscription(
            id = "s1",
            name = "harbor-home",
            url = "https://sub.provider-example.net/api/v1/client/subscribe?token=$token",
            nodes = 3,
            updatedAtEpochMs = 1_795_300_000_000,
            error = "GET https://sub.provider-example.net/api/v1/client/subscribe?token=$token: 403",
            totalBytes = 1L shl 40,
        )
        val engine = FakeEngine(groups, listOf(subscription), Status.Running(sinceEpochMs = 1_795_300_000_000))
        engine.setPerApp(PerApp(AppPolicy.OnlyListed, setOf("com.example.secretapp")))
        return engine
    }

    private val app = AppInfo("0.1", 1, "debug", listOf("arm64-v8a"), "16", 36, "Google", "Pixel 9")

    private fun TestScope.export(engine: Engine, now: Long = 1_795_300_200_000): ReportExport {
        val files = CrashFiles(crashDir)
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ReportExport(engine, files, CrashOffer(files, tmp.root.resolve("seen")), { outDir }, { app }, this, dispatcher) { now }
    }

    /** Every entry of the zip, by name. */
    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().decodeToString() }
    }

    /** A crash file of the app, from an exception whose message quotes the subscription URL. */
    private fun appCrash(at: Long): String {
        val error = IllegalStateException("fetch https://sub.provider-example.net/api?token=$token failed")
        CrashHandler(CrashFiles(crashDir, clock = { at }), "0.1 debug arm64-v8a-android", { _, _ -> }) { true }.uncaughtException(Thread.currentThread(), error)
        return CrashFiles(crashDir).list().last().name
    }

    @Test
    fun `the export holds no URL, address or host`() = runTest {
        val engine = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        engine.crashes = listOf(CrashFile(crash, crashDir.resolve(crash).length()))
        AppEvents.record(AppEventLevel.Info, "updateSubscription: ok after 1840 ms", 1_795_300_190_000)
        AppEvents.record(AppEventLevel.Warn, "start 3 failed: StartFailed after 412 ms", 1_795_300_191_000)

        val export = export(engine)
        export.startExport()
        advanceUntilIdle()

        val ready = export.export.value as ExportState.Ready
        val zip = entries(ready.file)
        assertEquals(setOf(ReportExport.REPORT_ENTRY, "crash/$crash"), zip.keys)
        val all = zip.values.joinToString("\n")
        for (secret in secrets + "com.example.secretapp") assertFalse(secret, secret in all)
        assertFalse("://" in all)
        assertNull(Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""").find(all)?.value)

        val report = Json.parseToJsonElement(zip.getValue(ReportExport.REPORT_ENTRY)).jsonObject
        val catalog = report["settings"]!!.jsonObject["catalog"]!!.jsonObject
        assertEquals(listOf("n-1a2b3c4d", "n-9f8e7d6c", "n-00112233"), catalog["nodeList"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content })
        assertEquals("s1", catalog["subscriptions"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("group:1", catalog["groups"]!!.jsonArray[0].jsonObject["selected"]!!.jsonPrimitive.content)
        assertEquals(2, report["events"]!!.jsonArray.size)
        assertEquals("Pixel 9", report["device"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertTrue(report["diagnostics"] is JsonObject)
    }

    /** Records the clears it is sent. */
    private class Recording(private val fake: FakeEngine, private val coreUp: Boolean = true) : Engine by fake {
        val cleared = mutableListOf<Pair<List<String>, Long>>()

        override suspend fun diagnostics(level: LogLevel?) =
            if (coreUp) fake.diagnostics(level) else Result.failure(EngineException(ErrorCode.Internal))

        override suspend fun clearCrashes(names: List<String>, mark: Long): Result<Unit> {
            cleared += names to mark
            return fake.clearCrashes(names, mark)
        }
    }

    @Test
    fun `a pick in the share sheet clears only the crash files the zip holds, once`() = runTest {
        val fake = fixtureEngine()
        val listed = listOf(appCrash(1_795_300_150_000), appCrash(1_795_300_151_000))
        fake.crashes = listed.map { CrashFile(it, crashDir.resolve(it).length()) }
        val engine = Recording(fake)
        val export = export(engine)
        export.startExport()
        // A crash written after the core's listing is neither exported nor cleared.
        val later = appCrash(1_795_300_152_000)
        advanceUntilIdle()

        val ready = export.take()!!
        assertEquals(listed.map { "crash/$it" }.toSet(), entries(ready.file).keys - ReportExport.REPORT_ENTRY)
        assertNull("the zip is shared once", export.take())
        assertTrue(engine.cleared.isEmpty())

        // The sheet's return comes before its pick, or after: the order is the system's.
        export.shared()
        advanceUntilIdle()
        assertTrue("the return alone clears nothing", engine.cleared.isEmpty())
        export.picked()
        export.picked()
        advanceUntilIdle()
        assertEquals(listOf(listed to 1_795_300_151_000L), engine.cleared)
        assertEquals(ExportState.Idle, export.export.value)
        assertTrue(crashDir.resolve(later).exists())
    }

    @Test
    fun `a share sheet that did not open clears nothing`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val engine = Recording(fake)
        val export = export(engine)
        export.startExport()
        advanceUntilIdle()
        export.take()
        export.shareFailed()
        export.shared()
        export.picked()
        advanceUntilIdle()
        assertEquals(ExportState.Failed, export.export.value)
        assertTrue(engine.cleared.isEmpty())
    }

    @Test
    fun `without the core the report goes out with the app's own listing, and nothing is cleared`() = runTest {
        val engine = Recording(fixtureEngine(), coreUp = false)
        val crash = appCrash(1_795_300_150_000)
        val export = export(engine)
        export.startExport()
        advanceUntilIdle()

        val ready = export.take()!!
        assertNull(ready.crashes)
        val zip = entries(ready.file)
        assertTrue("crash/$crash" in zip)
        val diagnostics = Json.parseToJsonElement(zip.getValue(ReportExport.REPORT_ENTRY)).jsonObject["diagnostics"]!!.jsonObject
        assertEquals("Internal", diagnostics["error"]!!.jsonPrimitive.content)
        export.picked()
        export.shared()
        advanceUntilIdle()
        assertTrue(engine.cleared.isEmpty())
    }

    @Test
    fun `exporting takes the crash offer down`() = runTest {
        appCrash(1_795_300_150_000)
        val files = CrashFiles(crashDir)
        val offer = CrashOffer(files, tmp.root.resolve("seen")).apply { check() }
        assertTrue(offer.offered.value)
        ReportExport(fixtureEngine(), files, offer, { outDir }, { app }, this, StandardTestDispatcher(testScheduler)).startExport()
        assertFalse(offer.offered.value)
    }

    @Test
    fun `a crash file still empty is left out of the zip and of the clear`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        // Another writer claimed this name and has not renamed its part onto it yet.
        crashDir.resolve("1795300151000.txt").createNewFile()
        fake.crashes = listOf(CrashFile(crash, 1), CrashFile("1795300151000.txt", 0))
        val engine = Recording(fake)
        val export = export(engine)
        export.startExport()
        advanceUntilIdle()
        val ready = export.take()!!
        assertEquals(setOf("crash/$crash"), entries(ready.file).keys - ReportExport.REPORT_ENTRY)
        export.picked()
        advanceUntilIdle()
        assertEquals(listOf(crash), engine.cleared.single().first)
    }

    @Test
    fun `a zip put back by an activity that ended is shared by the next`() = runTest {
        val export = export(fixtureEngine())
        export.startExport()
        advanceUntilIdle()
        val ready = export.take()!!
        export.putBack(ready)
        assertEquals(ready, export.export.value)
        assertEquals(ready, export.take())
    }

    @Test
    fun `a share sheet closed without a pick clears nothing`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val engine = Recording(fake)
        val export = export(engine)
        export.startExport()
        advanceUntilIdle()
        export.take()
        export.shared()
        advanceUntilIdle()
        assertEquals(ExportState.Idle, export.export.value)
        assertTrue(engine.cleared.isEmpty())
    }

    @Test
    fun `two exports in one second get a zip each`() = runTest {
        val export = export(fixtureEngine())
        export.startExport()
        advanceUntilIdle()
        val first = export.take()!!.file
        export.shared()
        export.startExport()
        advanceUntilIdle()
        val second = export.take()!!.file
        assertTrue(first.name, first.name.matches(Regex("doona-report-\\d{8}-\\d{6}\\.zip")))
        assertEquals(first.name.removeSuffix(".zip") + "-2.zip", second.name)
    }

    @Test
    fun `a shared zip stays while the app picked may read it, then goes`() = runTest {
        val export = export(fixtureEngine())
        export.startExport()
        advanceUntilIdle()
        val zip = export.take()!!.file
        export.shared()
        advanceTimeBy(ReportExport.SHARE_GRACE_MS - 1)
        runCurrent()
        assertTrue(zip.exists())
        advanceTimeBy(2)
        runCurrent()
        assertFalse(zip.exists())
    }

    @Test
    fun `a later run removes the zips an earlier one left, the recent ones not`() = runTest {
        val now = 1_795_300_200_000
        outDir.mkdirs()
        val old = outDir.resolve("doona-report-20261101-000000.zip").apply { writeText("x") }
        old.setLastModified(now - ReportExport.SHARE_GRACE_MS - 1)
        val recent = outDir.resolve("doona-report-20261123-000000.zip").apply { writeText("x") }
        recent.setLastModified(now - 1)
        export(fixtureEngine(), now).sweep()
        assertEquals(listOf(recent.name), outDir.list()!!.toList())
    }

    @Test
    fun `a zip no share sheet took is deleted`() = runTest {
        val export = export(fixtureEngine())
        export.startExport()
        advanceUntilIdle()
        val zip = export.take()!!.file
        export.shareFailed()
        advanceUntilIdle()
        assertFalse(zip.exists())
    }
}
