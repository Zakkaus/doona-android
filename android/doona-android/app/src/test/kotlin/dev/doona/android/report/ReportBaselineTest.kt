package dev.doona.android.report

import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.Engine
import dev.doona.android.engine.LogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class ReportBaselineTest : ExportFixture() {
    // The fixture is 25050034's output plus the dnsFailed count added since.
    @Test
    fun `default ZIP entries match 25050034 without looking up endpoints or IPs`() = runTest {
        val fake = fixtureEngine()
        val names = listOf("1795300140000.txt", "1795300150000.txt")
        crashDir.mkdirs()
        crashDir.resolve(names[0]).writeText(
            "source: core\natEpochMs: 1795300140000\nversion: 0.1.0 debug aarch64-android\nthread: doona-core\n" +
                "location: src/api.rs:42:7\nmessage: panic in doonacore::api\n\nbacktrace:\n0 libdoonacore.so 0x1f2e\n",
        )
        crashDir.resolve(names[1]).writeText(crashText("java.lang.IllegalStateException", null, true, app.crashVersion, 1_795_300_150_000))
        fake.crashes = names.map { CrashFile(it, crashDir.resolve(it).length()) }
        var nodeReads = 0
        var addressReads = 0
        val engine = object : Engine by fake {
            override suspend fun nodes(ids: List<String>?) = fake.nodes(ids).also { nodeReads++ }

            override suspend fun diagnostics(level: LogLevel?) = fake.diagnostics(level).map {
                it.copy(
                    json = """
                        {"core":{"version":"0.1.0","schema":2,"profile":"debug","target":"aarch64-android","features":[]},"level":"info",
                         "events":[{"atEpochMs":1795300140000,"level":"warn","name":"start","code":"internal","detail":null,"counts":{},"ms":4}],
                         "overwritten":0,"snapshot":{"atEpochMs":1795300140000,"revision":1,"running":true,"generation":1,"error":null,"warning":null},
                         "stats":{"traffic":{"upTotal":1,"downTotal":2,"activeFlows":3,"upPeak":4,"downPeak":5},"today":{"upTotal":6,"downTotal":7,"yesterdayByNow":null}},
                         "crashes":[{"name":"${names[0]}","bytes":${it.crashes[0].bytes}},{"name":"${names[1]}","bytes":${it.crashes[1].bytes}}],"crashMark":${it.crashMark}}
                    """.trimIndent(),
                )
            }
        }
        AppEvents.record(AppEventLevel.Info, "updateSubscription: ok after 1840 ms", 1_795_300_190_000)
        AppEvents.record(AppEventLevel.Warn, "init failed: IOException", 1_795_300_191_000)
        val reports = export(engine, ownAddresses = {
            addressReads++
            listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 84)))
        })
        val actual = entries(reports.build()!!.file)
        val files = listOf(ReportExport.REPORT_ENTRY) + names.map { ReportExport.CRASH_DIR + it }
        assertEquals(files.toSet(), actual.keys)
        for (name in files) {
            val original = requireNotNull(javaClass.getResource("/report/25050034/$name")).readText()
            assertEquals(name, original, actual.getValue(name))
        }
        assertEquals("default endpoint lookups", 0, nodeReads)
        assertEquals("default address lookups", 0, addressReads)
    }
}
