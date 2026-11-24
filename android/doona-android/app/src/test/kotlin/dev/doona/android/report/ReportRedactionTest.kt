package dev.doona.android.report

import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.Engine
import dev.doona.android.engine.LogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class ReportRedactionTest : ExportFixture() {
    @Test
    fun `only the requested category is disclosed in the ZIP`() = runTest {
        val engine = fixtureEngine()
        val ip = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 84))
        val reports = export(engine, ownAddresses = { listOf(ip) })
        val defaults = RedactionOptions()
        val options = listOf(
            defaults,
            defaults.copy(showNodeEndpoints = true),
            defaults.copy(showOwnAddresses = true),
            defaults.copy(showVisitedDomains = true),
            defaults.copy(showAppNames = true),
        )
        val sensitive = listOf("node-1.example.net:443", "192.0.2.84", VISITED, PACKAGE)
        for ((index, option) in options.withIndex()) {
            AppEvents.clear()
            AppEvents.record(AppEventLevel.Warn, ACTION, visitedDomain = VISITED, appPackage = PACKAGE)
            val report = entries(reports.build(option)!!.file).getValue(ReportExport.REPORT_ENTRY)
            assertEquals(listOfNotNull(sensitive.getOrNull(index - 1)), sensitive.filter { it in report })
            for (secret in secrets) assertFalse(secret, secret in report)
        }
    }

    @Test
    fun `repeated domains and packages have stable distinct aliases`() = runTest {
        val engine = fixtureEngine()
        AppEvents.record(AppEventLevel.Warn, ACTION, visitedDomain = VISITED, appPackage = PACKAGE)
        AppEvents.record(AppEventLevel.Warn, ACTION, visitedDomain = "other.example", appPackage = "org.example.other")
        AppEvents.record(AppEventLevel.Warn, ACTION, visitedDomain = VISITED, appPackage = PACKAGE)
        val report = entries(export(engine).build()!!.file).getValue(ReportExport.REPORT_ENTRY)
        val events = Json.parseToJsonElement(report).jsonObject.getValue("events").jsonArray.map { it.jsonObject }
        val first = events.first()
        val other = events[1]
        val last = events.last()
        for ((key, prefix) in listOf("domain" to "host-", "appName" to "app-")) {
            assertTrue(first.getValue(key).toString().startsWith("\"$prefix"))
            assertEquals(first[key], last[key])
            assertFalse(first[key] == other[key])
        }
    }

    @Test
    fun `all switches leave untrusted ZIP text and unknown fields private`() = runTest {
        val fake = fixtureEngine()
        val crashName = "1795300140000.txt"
        val sub = fake.catalog.value!!.subscriptions.single().url
        val credential = "test-user:test-pass"
        val token = "fixture-token-8842"
        val raw = "$sub $credential $token"
        crashDir.mkdirs()
        crashDir.resolve(crashName).writeText(
            "source: core\natEpochMs: 1795300140000\nmessage: $raw\n\nbacktrace:\n0 libdoonacore.so 0x1f2e\n1 $credential 0x1f3e\n",
        )
        fake.crashes = listOf(CrashFile(crashName, crashDir.resolve(crashName).length()))
        AppEvents.record(AppEventLevel.Warn, "token=$token")
        AppEvents.record(AppEventLevel.Warn, "GET $sub", visitedDomain = sub, appPackage = credential)
        val engine = object : Engine by fake {
            override suspend fun diagnostics(level: LogLevel?) = fake.diagnostics(level).map {
                it.copy(
                    json = """
                        {"core":{"version":"0.1.0","target":"$credential","features":["$token"]},
                         "events":[{"name":"start","code":"internal","detail":"$token","counts":{"$credential":42},"payload":"$sub"}],
                         "stats":{"traffic":{"upTotal":12,"payload":"$token"}},"$sub":{"password":"$credential"}}
                    """.trimIndent(),
                )
            }
        }
        val files = entries(export(engine).build(SHOW_ALL)!!.file)
        val report = files.getValue(ReportExport.REPORT_ENTRY)
        val crash = files.getValue("crash/$crashName")
        assertTrue("\"code\": \"internal\"" in report)
        assertTrue("\"upTotal\": 12" in report)
        assertTrue("0 libdoonacore.so 0x1f2e" in crash)
        val zipText = files.values.joinToString("\n")
        for (secret in secrets + listOf(sub, credential, token)) assertFalse(secret, secret in zipText)
    }

    companion object {
        private const val ACTION = "command=start phase=complete outcome=failed code=Internal"
        private const val VISITED = "visited.example"
        private const val PACKAGE = "org.example.browser"
        private val SHOW_ALL = RedactionOptions(true, true, true, true)
    }
}
