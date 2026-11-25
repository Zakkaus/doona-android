package dev.doona.android.report

import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ReportRedactionTest : ExportFixture() {
    @Test
    fun `defaults and individual switches disclose only the selected values`() = runTest {
        val engine = fixtureEngine()
        val ip = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 84))
        val reports = export(engine, ownAddresses = { listOf(ip) })
        val defaults = RedactionOptions()
        val cases = listOf(
            defaults to listOf(PACKAGE),
            defaults.copy(showNodeEndpoints = true) to listOf(ENDPOINT, PACKAGE),
            defaults.copy(showOwnAddresses = true) to listOf(OWN_IP, PACKAGE),
            defaults.copy(showVisitedDomains = true) to listOf(VISITED, PACKAGE),
            defaults.copy(showAppNames = false) to emptyList(),
        )
        val values = listOf(ENDPOINT, OWN_IP, VISITED, PACKAGE)
        for ((option, expected) in cases) {
            AppEvents.clear()
            AppEvents.record(AppEventLevel.Warn, ACTION, visitedDomain = VISITED, appPackage = PACKAGE)
            val result = reports.build(option, FAILURE)!!
            val report = entries(result.file).getValue(ReportExport.REPORT_ENTRY)
            assertEquals("ZIP ($option)", expected, values.filter { it in report })
            val event = Json.parseToJsonElement(report).jsonObject.getValue("events").jsonArray.single().jsonObject
            val fields = buildSet {
                addAll(listOf("atEpochMs", "level", "text"))
                if (VISITED in expected) add("domain")
                if (PACKAGE in expected) add("appName")
            }
            assertEquals("ZIP event keys ($option)", fields, event.keys)

            val draft = draft(result)
            val suffix = buildString {
                if (VISITED in expected) append(" domain=$VISITED")
                if (PACKAGE in expected) append(" app=$PACKAGE")
            }
            assertTrue("draft action ($option)", draft.lineSequence().any { it.endsWith("$ACTION$suffix") })
            assertEquals("draft ($option)", expected.filter { it == VISITED || it == PACKAGE }, values.filter { it in draft })
        }
    }

    @Test
    fun `fixed failure warnings remain readable without admitting exception messages`() = runTest {
        val safe = listOf(
            "init failed: NoClassDefFoundError",
            "startForeground threw SecurityException",
            "updateSubscription reply unreadable: SerializationException",
            "per-app policy unreadable: IOException",
            "snapshot unreadable: IllegalStateException",
            "subscription-refresh: settings read failed (IOException)",
            "subscription-refresh: schedule write failed (SQLiteException)",
            "subscription-refresh: update failed (FetchFailed)",
        )
        val unsafe = listOf("init failed: https://example.test/key", "nodes reply unreadable: IOException: password=secret")
        (safe + unsafe).forEach { AppEvents.record(AppEventLevel.Warn, it) }
        val report = entries(export(fixtureEngine()).build()!!.file).getValue(ReportExport.REPORT_ENTRY)
        val texts = Json.parseToJsonElement(report).jsonObject.getValue("events").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
        assertEquals(safe + listOf(REDACTED, REDACTED), texts)
    }

    @Test
    fun `all switches leave untrusted ZIP text and unknown fields private`() = runTest {
        val fake = fixtureEngine()
        val crashName = "1795300140000.txt"
        val appCrash = "1795300150000.txt"
        val sub = fake.catalog.value!!.subscriptions.single().url
        val credential = "test-user:test-pass"
        val token = "fixture-token-8842"
        val raw = "$sub $credential $token"
        val nodeCredential = "u:p@h"
        val nodeUrl = "https://node.example/sub?token=node-token"
        val addressHost = "fixture-address-token"
        val addressBytes = ByteArray(16).apply {
            this[0] = 0x20
            this[1] = 1
            this[15] = 3
        }
        val address = Inet6Address.getByAddress(addressHost, addressBytes, 42)
        crashDir.mkdirs()
        crashDir.resolve(crashName).writeText(
            "source: core\natEpochMs: 1795300140000\nmessage: $raw\n\nbacktrace:\n0 libdoonacore.so 0x1f2e\n1 $credential 0x1f3e\n",
        )
        val appText = crashText("java.lang.IllegalStateException", null, true, app.crashVersion, 1_795_300_150_000)
        crashDir.resolve(appCrash).writeText(appText + "message: app-crash-token $raw\n  at $credential\n")
        fake.crashes = listOf(crashName, appCrash).map { CrashFile(it, crashDir.resolve(it).length()) }
        AppEvents.record(AppEventLevel.Warn, "token=$token")
        AppEvents.record(AppEventLevel.Warn, "GET $sub")
        AppEvents.record(AppEventLevel.Warn, ACTION, visitedDomain = sub, appPackage = credential)
        AppEvents.record(AppEventLevel.Warn, ACTION, visitedDomain = token, appPackage = token)
        val engine = object : Engine by fake {
            override suspend fun nodes(ids: List<String>?) = fake.nodes(ids).map { nodes ->
                listOf(nodes[0].copy(server = nodeCredential), nodes[1].copy(server = nodeUrl), nodes[2].copy(server = "2001:db8::21"))
            }

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
        val result = export(engine, ownAddresses = { listOf(address) }).build(SHOW_ALL, FAILURE)!!
        val files = entries(result.file)
        val report = files.getValue(ReportExport.REPORT_ENTRY)
        val crash = files.getValue("crash/$crashName")
        assertTrue("\"code\": \"internal\"" in report)
        assertTrue("\"upTotal\": 12" in report)
        assertTrue("0 libdoonacore.so 0x1f2e" in crash)
        assertTrue("[2001:db8::21]:443" in report)
        assertTrue(address.hostAddress!!.substringBefore('%') in report)
        assertTrue(appText.trimEnd() in files.getValue("crash/$appCrash"))
        val draft = draft(result)
        assertTrue(ACTION in draft)
        assertFalse("[2001:db8::21]:443" in draft)
        val zipText = files.values.joinToString("\n")
        val planted = secrets + listOf(sub, credential, token, nodeCredential, nodeUrl, addressHost, "%42", "app-crash-token")
        assertEquals("exported planted secrets", emptyList<String>(), planted.filter { it in zipText || it in draft })
    }

    private fun draft(result: ReportZip): String = URLDecoder.decode(URI(result.issueLink!!).rawQuery, Charsets.UTF_8.name())

    companion object {
        private const val ACTION = "command=start phase=complete outcome=failed code=Internal"
        private const val ENDPOINT = "node-1.example.net:443"
        private const val OWN_IP = "192.0.2.84"
        private const val VISITED = "visited.example"
        private const val PACKAGE = "org.example.browser"
        private val FAILURE = ReportFailure(ErrorCode.Internal, "start", Instant.EPOCH)
        private val SHOW_ALL = RedactionOptions(showNodeEndpoints = true, showOwnAddresses = true, showVisitedDomains = true, showAppNames = true)
    }
}
