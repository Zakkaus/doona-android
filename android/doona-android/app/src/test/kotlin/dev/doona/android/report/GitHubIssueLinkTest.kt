package dev.doona.android.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.time.Instant

class GitHubIssueLinkTest {
    private val at = Instant.parse("2026-11-24T08:09:10Z")
    private val app = AppInfo("0.1", 1, "debug", listOf("arm64-v8a"), "16", 36, "Google", "Pixel 9")

    @Test
    fun `draft includes context and the last fifty actions in chronological order`() {
        val events = (0..51).map(::event) + AppEvent(0, AppEventLevel.Info, "health check: data path down")
        val uri = URI(link(events))
        assertEquals("https", uri.scheme)
        assertEquals("github.com", uri.host)
        assertEquals("/Zakkaus/doona-android/issues/new", uri.path)
        val query = query(uri)
        assertEquals("Doona: start (Internal)", query.getValue("title"))
        val body = query.getValue("body")
        for (expected in listOf("Error code: Internal", "Command: start", "Time (UTC): $at", "App: 0.1", "Core: 0.2.0", "Android: 16", "Device: Pixel 9", "Please attach the exported diagnostic ZIP.")) {
            assertTrue(expected, expected in body)
        }
        assertEquals((2..51).toList(), indices(body))
    }

    @Test
    fun `encoded length removes only the oldest actions`() {
        val domain = List(4) { "a".repeat(60) }.joinToString(".")
        val appPackage = "org." + "a".repeat(240)
        val events = (0..199).map { event(it).copy(visitedDomain = domain, appPackage = appPackage) }
        val url = link(events, RedactionOptions(showVisitedDomains = true, showAppNames = true))
        assertTrue(url.length < 8000)
        val body = query(URI(url)).getValue("body")
        val included = indices(body)
        assertTrue(included.size in 1..49)
        assertEquals((200 - included.size until 200).toList(), included)
        val nextLine = query(URI(link(events.take(200 - included.size), RedactionOptions(showVisitedDomains = true, showAppNames = true))))
            .getValue("body").lineSequence().last { it.contains("elapsedMs=") } + "\n"
        assertTrue(url.length + java.net.URLEncoder.encode(nextLine, Charsets.UTF_8.name()).length >= 8000)
    }

    @Test
    fun `all switches still reject free text in the draft`() {
        val sub = "https://subscription.example/sub?token=fixture-token"
        val credential = "fixture-user:fixture-password"
        val events = listOf(
            event(1).copy(visitedDomain = "visited.example", appPackage = "org.example.browser"),
            event(2).copy(text = "token=fixture-token $credential $sub"),
            event(3).copy(visitedDomain = sub, appPackage = credential),
        )
        val url = GitHubIssueLink.build("fixture-token", credential, at, app, sub, events, RedactionOptions(true, true, true, true))
        val query = query(URI(url))
        val body = query.getValue("body")
        assertTrue("domain=visited.example app=org.example.browser" in body)
        assertEquals(listOf(1, 3), indices(body))
        for (secret in listOf(sub, "subscription.example", "fixture-token", "fixture-user", "fixture-password")) {
            assertFalse(secret, query.values.any { secret in it })
        }
    }

    @Test
    fun `hidden identifiers are relabelled consistently in the draft`() {
        val first = event(1).copy(visitedDomain = "visited.example", appPackage = "org.example.browser")
        val other = event(2).copy(visitedDomain = "other.example", appPackage = "org.example.other")
        val body = query(URI(link(listOf(first, other, first)))).getValue("body")
        val identities = Regex("domain=host-\\d+ app=app-\\d+").findAll(body).map { it.value }.toList()
        assertEquals(3, identities.size)
        assertEquals(identities.first(), identities.last())
        assertFalse(identities.first() == identities[1])
        assertFalse("visited.example" in body || "org.example.browser" in body)
    }

    private fun link(events: List<AppEvent>, options: RedactionOptions = RedactionOptions()) =
        GitHubIssueLink.build("Internal", "start", at, app, "0.2.0", events, options)

    private fun event(index: Int) = AppEvent(
        at.toEpochMilli() + index,
        AppEventLevel.Warn,
        "command=start phase=complete outcome=failed code=Internal elapsedMs=$index",
    )

    private fun indices(body: String) = Regex("elapsedMs=(\\d+)").findAll(body).map { it.groupValues[1].toInt() }.toList()

    private fun query(uri: URI) = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8.name()) }
}
