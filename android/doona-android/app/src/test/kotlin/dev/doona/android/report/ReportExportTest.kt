package dev.doona.android.report

import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export: a zip that holds no URL, address or host of the catalog it was made from, and a clear after the share that
 * takes only the crash files the zip holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportExportTest : ExportFixture() {
    @Test
    fun `the export holds no URL, address or host`() = runTest {
        val engine = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        engine.crashes = listOf(CrashFile(crash, crashDir.resolve(crash).length()))
        AppEvents.record(AppEventLevel.Info, "updateSubscription: ok after 1840 ms", 1_795_300_190_000)
        AppEvents.record(AppEventLevel.Warn, "command=start phase=complete outcome=failed code=StartFailed elapsedMs=412", 1_795_300_191_000)

        val zip = entries(export(engine).build()!!.file)
        assertEquals(setOf(ReportExport.REPORT_ENTRY, "crash/$crash"), zip.keys)
        val all = zip.values.joinToString("\n")
        for (secret in secrets + "com.example.secretapp") assertFalse(secret, secret in all)
        assertFalse("://" in all)
        assertNull(Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""").find(all)?.value)

        val report = Json.parseToJsonElement(zip.getValue(ReportExport.REPORT_ENTRY)).jsonObject
        val catalog = report["settings"]!!.jsonObject["catalog"]!!.jsonObject
        val nodes = catalog["nodeList"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("n-1a2b3c4d", "n-9f8e7d6c", "n-00112233"), nodes.map { it["code"]!!.jsonPrimitive.content })
        assertTrue(nodes.none { "endpoint" in it })
        assertFalse("ownAddresses" in report.getValue("device").jsonObject)
        assertEquals("s1", catalog["subscriptions"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("group:1", catalog["groups"]!!.jsonArray[0].jsonObject["selected"]!!.jsonPrimitive.content)
        assertEquals(2, report["events"]!!.jsonArray.size)
        assertEquals("Pixel 9", report["device"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertTrue(report["diagnostics"] is JsonObject)
    }

    @Test
    fun exportsSplitGroupSelectionsWithoutRawIds() = runTest {
        val engine = fixtureEngine()
        val proxy = engine.groups.value.first()
        val udpId = "n-9f8e7d6c5b4a3921"
        engine.select(proxy.id, udpId, Network.Udp).getOrThrow()

        val json = entries(export(engine).build()!!.file).getValue(ReportExport.REPORT_ENTRY)
        val catalog = Json.parseToJsonElement(json).jsonObject.getValue("settings").jsonObject.getValue("catalog").jsonObject
        val group = catalog.getValue("groups").jsonArray.first().jsonObject
        assertEquals("group:1", group.getValue("selected").jsonPrimitive.content)
        assertFalse(group.getValue("pinned").jsonPrimitive.boolean)
        assertEquals("group:1", group.getValue("tcpSelected").jsonPrimitive.content)
        assertFalse(group.getValue("tcpPinned").jsonPrimitive.boolean)
        assertEquals("n-9f8e7d6c", group.getValue("udpSelected").jsonPrimitive.content)
        assertTrue(group.getValue("udpPinned").jsonPrimitive.boolean)
        assertFalse("g-Auto" in json)
        assertFalse(udpId in json)
    }

    @Test
    fun countsDnsProbeFailuresSeparatelyAndOncePerNode() = runTest {
        val engine = fixtureEngine()
        engine.groups.value = engine.groups.value.map { group ->
            val members = group.members.map { node ->
                if (node.id == "n-0011223344556677") node.copy(failed = true, error = ErrorCode.DnsFailed) else node
            }
            group.copy(members = members)
        }
        val json = entries(export(engine).build()!!.file).getValue(ReportExport.REPORT_ENTRY)
        val catalog = Json.parseToJsonElement(json).jsonObject.getValue("settings").jsonObject.getValue("catalog").jsonObject
        val counts = catalog.getValue("nodes").jsonObject
        assertEquals(2, counts.getValue("failed").jsonPrimitive.int)
        assertEquals(1, counts.getValue("localNetwork").jsonPrimitive.int)
        assertEquals(1, counts.getValue("dnsFailed").jsonPrimitive.int)
    }

    @Test
    fun `a written report contains Debug diagnostics before the capture ends`() = runTest {
        val engine = fixtureEngine()
        val logLevels = LogLevelController(engine::setLogLevel, backgroundScope) { testScheduler.currentTime }
        logLevels.set(LogLevel.Debug).getOrThrow()

        val report = entries(export(engine, logLevels = logLevels).build()!!.file).getValue(ReportExport.REPORT_ENTRY)
        val diagnostics = Json.parseToJsonElement(report).jsonObject.getValue("diagnostics").jsonObject
        assertEquals("debug", diagnostics.getValue("level").jsonPrimitive.content)
        runCurrent()
        assertEquals(LogLevel.Info, logLevels.level.value)
        assertEquals(LogLevel.Info, engine.diagnostics().getOrThrow().level)
        assertEquals("command=export phase=write outcome=ok code=ok", AppEvents.snapshot().last { it.text.startsWith("command=") }.text)
    }

    @Test
    fun `the report after a restart carries the crash's last fifty bounded events`() = runTest {
        val text = "command=updateSubscription phase=complete outcome=ok code=ok"
        val at = 1_795_300_100_000L
        repeat(220) { AppEvents.record(AppEventLevel.Info, "$text elapsedMs=$it", at + it) }
        val secret = "command=select phase=complete outcome=failed code=privatehost"
        AppEvents.record(AppEventLevel.Warn, secret, at + 220)
        AppEvents.record(AppEventLevel.Warn, "GET https://private.example/secret", at + 221)
        val crash = appCrash(1_795_300_150_000)
        AppEvents.clear()

        val file = crashDir.resolve(crash)
        assertTrue(file.length() <= 64 * 1024)
        val expected = (170 until 220).map { "$BREADCRUMB${at + it} Info $text elapsedMs=$it" }
        assertEquals(expected, file.readLines().filter { it.startsWith(BREADCRUMB) })
        val report = export(Recording(fixtureEngine(), coreUp = false)).build()!!
        assertEquals(file.readText(), entries(report.file).getValue("crash/$crash"))
    }

    @Test
    fun `a report is ready even while Info is blocked`() = runTest {
        val engine = fixtureEngine()
        val resetStarted = CompletableDeferred<Unit>()
        val resetAllowed = CompletableDeferred<Unit>()
        val levels = LogLevelController({ level ->
            if (level == LogLevel.Info) {
                resetStarted.complete(Unit)
                resetAllowed.await()
            }
            engine.setLogLevel(level)
        }, backgroundScope) { testScheduler.currentTime }
        levels.set(LogLevel.Debug).getOrThrow()

        val building = async { export(engine, logLevels = levels).build() }
        runCurrent()
        assertTrue(resetStarted.isCompleted)
        assertTrue("reset is holding up the report", building.isCompleted)
        val zip = building.await()!!.file
        assertTrue(ReportExport.REPORT_ENTRY in entries(zip))
        resetAllowed.complete(Unit)
        runCurrent()
        assertEquals(LogLevel.Info, engine.diagnostics().getOrThrow().level)
        assertTrue(zip.isFile)
    }

    @Test
    fun `export checks the elapsed deadline before the diagnostics have returned`() = runTest {
        var elapsed = 0L
        val fake = fixtureEngine()
        val levels = LogLevelController(fake::setLogLevel, backgroundScope) { elapsed }
        val diagnosticsAllowed = CompletableDeferred<Unit>()
        val engine = object : Engine by fake {
            override suspend fun diagnostics(level: LogLevel?) = fake.diagnostics(level).also { diagnosticsAllowed.await() }
        }
        levels.set(LogLevel.Debug).getOrThrow()
        runCurrent()
        elapsed += 3_600_000

        val building = async { export(engine, logLevels = levels).build() }
        runCurrent()
        assertEquals(LogLevel.Info, fake.diagnostics().getOrThrow().level)
        diagnosticsAllowed.complete(Unit)
        assertTrue(building.await()!!.file.isFile)
    }

    @Test
    fun `cancellation returning from IO keeps a completed report`() = runTest {
        val engine = fixtureEngine()
        val levels = LogLevelController(engine::setLogLevel, backgroundScope) { testScheduler.currentTime }
        val files = CrashFiles(crashDir)
        val building = Job(coroutineContext[Job])
        val report = ReportExport(
            engine,
            files,
            CrashOffer(files, tmp.root.resolve("seen")),
            {
                building.cancel()
                outDir
            },
            tokens,
            { app },
            this,
            StandardTestDispatcher(testScheduler),
            levels,
            testFlowEvents(StandardTestDispatcher(testScheduler)),
        )
        async(building) { report.build() }.join()
        runCurrent()

        val zips = outDir.listFiles()!!.filter { it.extension == "zip" }
        assertEquals("the completed zip was deleted", 1, zips.size)
        assertTrue(ReportExport.REPORT_ENTRY in entries(zips.single()))
    }

    @Test
    fun `a pick in the share sheet clears only the crash files the zip holds, once`() = runTest {
        val fake = fixtureEngine()
        val listed = listOf(appCrash(1_795_300_150_000), appCrash(1_795_300_151_000))
        fake.crashes = listed.map { CrashFile(it, crashDir.resolve(it).length()) }
        val engine = Recording(fake)
        val export = export(engine)
        val ready = export.build()!!
        // A crash written after the core's listing is neither exported nor cleared.
        val later = appCrash(1_795_300_152_000)
        assertEquals(listed.map { "crash/$it" }.toSet(), entries(ready.file).keys - ReportExport.REPORT_ENTRY)
        assertTrue(engine.cleared.isEmpty())

        // The sheet's return comes before its pick, or after: the order is the system's.
        export.returned(ready)
        runCurrent()
        assertTrue("the return alone clears nothing", engine.cleared.isEmpty())
        export.picked(ready.token)
        export.picked(ready.token)
        advanceUntilIdle()
        assertEquals(listOf(listed to 1_795_300_151_000L), engine.cleared)
        assertTrue(crashDir.resolve(later).exists())
    }

    @Test
    fun `a share sheet that did not open clears nothing`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val engine = Recording(fake)
        val export = export(engine)
        val ready = export.build()!!
        export.notOpened(ready)
        advanceUntilIdle()
        export.picked(ready.token)
        advanceUntilIdle()
        assertTrue(engine.cleared.isEmpty())
    }

    @Test
    fun `without the core the report goes out with the app's own listing, and nothing is cleared`() = runTest {
        val engine = Recording(fixtureEngine(), coreUp = false)
        val crash = appCrash(1_795_300_150_000)
        val export = export(engine)
        val ready = export.build()!!
        assertNull(ready.crashes)
        val zip = entries(ready.file)
        assertTrue("crash/$crash" in zip)
        val diagnostics = Json.parseToJsonElement(zip.getValue(ReportExport.REPORT_ENTRY)).jsonObject["diagnostics"]!!.jsonObject
        assertEquals("Internal", diagnostics["error"]!!.jsonPrimitive.content)
        export.picked(ready.token)
        export.returned(ready)
        advanceUntilIdle()
        assertTrue(engine.cleared.isEmpty())
    }

    @Test
    fun `exporting takes the crash offer down`() = runTest {
        appCrash(1_795_300_150_000)
        val files = CrashFiles(crashDir)
        val offer = CrashOffer(files, tmp.root.resolve("seen")).apply { check() }
        assertTrue(offer.offered.value)
        val engine = fixtureEngine()
        val logLevels = LogLevelController(engine::setLogLevel, backgroundScope) { testScheduler.currentTime }
        val io = StandardTestDispatcher(testScheduler)
        ReportExport(engine, files, offer, { outDir }, tokens, { app }, this, io, logLevels, testFlowEvents(io)).build()
        assertFalse(offer.offered.value)
    }

    @Test
    fun `a share sheet closed without a pick clears nothing`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val engine = Recording(fake)
        val export = export(engine)
        export.returned(export.build()!!)
        advanceUntilIdle()
        assertTrue(engine.cleared.isEmpty())
    }

    @Test
    fun `two exports in one second get a zip each`() = runTest {
        val export = export(fixtureEngine())
        val first = export.build()!!.file
        val second = export.build()!!.file
        assertTrue(first.name, first.name.matches(Regex("doona-report-\\d{8}-\\d{6}\\.zip")))
        assertEquals(first.name.removeSuffix(".zip") + "-2.zip", second.name)
    }

    @Test
    fun `a shared zip stays while the app picked may read it, then goes`() = runTest {
        val export = export(fixtureEngine())
        val ready = export.build()!!
        val zip = ready.file
        export.returned(ready)
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
        val ready = export.build()!!
        export.notOpened(ready)
        advanceUntilIdle()
        val zip = ready.file
        assertFalse(zip.exists())
    }

    @Test
    fun `a late pick in an earlier sheet clears only that export's crash files`() = runTest {
        val fake = fixtureEngine()
        val first = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(first, 1))
        val engine = Recording(fake)
        val export = export(engine)
        val firstExport = export.build()!!
        export.returned(firstExport)
        // A crash after the first export: only the second holds it.
        val second = appCrash(1_795_300_160_000)
        fake.crashes = listOf(CrashFile(first, 1), CrashFile(second, 1))
        export.build()!!
        export.picked(firstExport.token)
        advanceUntilIdle()
        assertEquals(listOf(listOf(first) to 1_795_300_150_000L), engine.cleared)
    }

    @Test
    fun `a pick reaches an export the process made before it ended`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val engine = Recording(fake)
        val before = export(engine)
        val ready = before.build()!!
        // The process ends with the sheet open; the pick starts a new one.
        val after = export(engine)
        after.picked(ready.token)
        advanceUntilIdle()
        assertEquals(listOf(listOf(crash) to 1_795_300_150_000L), engine.cleared)
    }

    @Test
    fun `a zip an ended run was writing is removed at the next start, however recent`() = runTest {
        outDir.mkdirs()
        val half = outDir.resolve(".doona-report-1234.part").apply { writeText("x") }
        export(fixtureEngine()).sweep()
        assertFalse(half.exists())
    }

    @Test
    fun `an export whose token cannot be saved fails, and leaves no zip to share`() = runTest {
        // A directory holds the tokens' name, so the save cannot rename onto it.
        tmp.root.resolve("report-exports.json").resolve("kept").apply { parentFile!!.mkdirs() }.writeText("x")
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        assertNull(export(Recording(fake)).build())
        assertTrue(outDir.list().orEmpty().none { it.endsWith(".zip") })
    }

    @Test
    fun `a clear that failed is sent again at the next start, and its export is forgotten only once one succeeds`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val engine = Recording(fake, failures = 1)
        val export = export(engine)
        export.picked(export.build()!!.token)
        advanceUntilIdle()
        assertEquals(1, engine.cleared.size)
        export(engine).sweep()
        advanceUntilIdle()
        assertEquals(List(2) { listOf(crash) to 1_795_300_150_000L }, engine.cleared)
        export(engine).sweep()
        advanceUntilIdle()
        assertEquals("sent again after it succeeded", 2, engine.cleared.size)
    }

    @Test
    fun `a tokens file that cannot be read fails the export rather than losing the clears it holds`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val engine = Recording(fake, failures = 1)
        val export = export(engine)
        val ready = export.build()!!
        export.picked(ready.token)
        export.returned(ready)
        advanceUntilIdle()
        val saved = tmp.root.resolve("report-exports.json")
        assertTrue(saved.setReadable(false))
        try {
            assertNull(export.build())
        } finally {
            saved.setReadable(true)
        }
        export(engine).sweep()
        advanceUntilIdle()
        assertEquals(List(2) { listOf(crash) to 1_795_300_150_000L }, engine.cleared)
    }

    @Test
    fun `a process that ends during the clear leaves it to the next start`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val dying = Recording(fake, hangs = true)
        val process = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val before = export(dying, scope = process)
        before.picked(before.build()!!.token)
        advanceUntilIdle()
        assertEquals(1, dying.cleared.size)
        process.cancel()
        val engine = Recording(fake)
        export(engine).sweep()
        advanceUntilIdle()
        assertEquals(listOf(listOf(crash) to 1_795_300_150_000L), engine.cleared)
    }

    @Test
    fun `a pick in the first of nine sheets still clears that export's crash files`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val engine = Recording(fake)
        val export = export(engine)
        val tokens = (1..9).map {
            export.build()!!.also(export::returned).token
        }
        export.picked(tokens.first())
        advanceUntilIdle()
        assertEquals(listOf(listOf(crash) to 1_795_300_150_000L), engine.cleared)
    }

    @Test
    fun `an empty file the core lists is neither exported nor cleared`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        crashDir.resolve("1795300140000.txt").writeText("")
        fake.crashes = listOf(CrashFile("1795300140000.txt", 0), CrashFile(crash, 1))
        val engine = Recording(fake)
        val export = export(engine)
        val ready = export.build()!!
        assertEquals(setOf("crash/$crash"), entries(ready.file).keys - ReportExport.REPORT_ENTRY)
        export.picked(ready.token)
        advanceUntilIdle()
        assertEquals(listOf(listOf(crash) to 1_795_300_150_000L), engine.cleared)
    }

    @Test
    fun `an app crash file of an earlier format is left out of the zip and the report, and is not cleared`() = runTest {
        crashDir.mkdirs()
        val core = "1795300140000.txt"
        val coreText = "source: core\natEpochMs: 1795300140000\nlocation: src/api.rs:42:7\nmessage: panic in doonacore::api\n\nbacktrace:\n0 libdoonacore.so 0x1f2e\n"
        crashDir.resolve(core).writeText(coreText)
        val placeholder = "1795300145000.txt"
        crashDir.resolve(placeholder).writeText("")
        // Written before the format line: its names were never checked, and a method name may hold a line break.
        val old = "1795300150000.txt"
        crashDir.resolve(old).writeText(
            "source: app\natEpochMs: 1795300150000\nversion: 0.1 debug arm64-v8a-android\nthread: main\n" +
                "location: dev.doona.android.report.CrashFiles.write:66\nmessage: uncaught exception\n\nbacktrace:\n" +
                "exception: java.lang.IllegalStateException\n  at dev.doona.android.report.CrashFiles.write\nhunter2:66\n" +
                "cause: dev.doona.customer.example.Leak\n",
        )
        val current = appCrash(1_795_300_160_000)
        val fake = fixtureEngine()
        fake.crashes = listOf(CrashFile(core, 1), CrashFile(placeholder, 0), CrashFile(old, 1), CrashFile(current, 1))
        val engine = Recording(fake)
        val export = export(engine)
        val ready = export.build()!!
        val zip = entries(ready.file)
        assertEquals(setOf(ReportExport.REPORT_ENTRY, "crash/$core", "crash/$current"), zip.keys)
        assertEquals(coreText, zip.getValue("crash/$core"))
        val report = Json.parseToJsonElement(zip.getValue(ReportExport.REPORT_ENTRY)).jsonObject
        assertEquals(listOf(core, current), report["crashes"]!!.jsonArray.map { it.jsonPrimitive.content })
        val listed = report["diagnostics"]!!.jsonObject["crashes"]!!.jsonArray
        assertEquals(listOf(core, current), listed.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        export.picked(ready.token)
        advanceUntilIdle()
        assertEquals(listOf(listOf(core, current) to 1_795_300_160_000L), engine.cleared)
    }

    @Test
    fun `of an app crash file only the lines its format has go out, its names checked again`() = runTest {
        crashDir.mkdirs()
        val name = "1795300150000.txt"
        val kept = "source: app\nformat: 1\natEpochMs: 1795300150000\nversion: 0.1 debug arm64-v8a-android\nthread: main\n" +
            "location: dev.doona.android.report.CrashFiles.write:66\nmessage: uncaught exception\n\nbacktrace:\n" +
            "exception: java.lang.IllegalStateException\n  at dev.doona.android.report.CrashFiles.write:66\n"
        crashDir.resolve(name).writeText(
            kept +
                // A method name that holds a line break, a password after it.
                "  at dev.doona.android.report.CrashFiles.run\nhunter2:7\n" +
                "  at dev.doona.android.report.CrashFiles.run:12\nhunter2 at sub.example.net:7\n" +
                "  at dev.doona.android.report.CrashFiles.hunter2:8\n" +
                "cause: dev.doona.customer.example.Leak\nmessage: GET https://sub.example.net/?token=tk_5ecret\n",
        )
        val fake = fixtureEngine()
        fake.crashes = listOf(CrashFile(name, 1))
        val export = export(fake)
        val text = entries(export.build()!!.file).getValue("crash/$name")
        assertEquals(kept + "  at $REDACTED\n  at $REDACTED\n", text)
    }

    @Test
    fun `without the core the app trims what earlier versions left to the newest five before it lists them`() = runTest {
        crashDir.mkdirs()
        val text = crashText("java.lang.IllegalStateException", emptyList(), true, "0.1 debug arm64-v8a-android", 1_795_300_150_000)
        val names = (1..6).map { n -> "179530015000$n.txt".also { crashDir.resolve(it).writeText(text) } }
        val export = export(Recording(fixtureEngine(), coreUp = false))
        val zip = entries(export.build()!!.file)
        assertEquals(names.drop(1).map { "crash/$it" }.toSet(), zip.keys - ReportExport.REPORT_ENTRY)
        assertFalse(crashDir.resolve(names.first()).exists())
    }
}
