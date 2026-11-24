package dev.doona.android.report

import dev.doona.android.engine.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
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
        val events = (0..199).map { index ->
            AppEvent(
                Long.MAX_VALUE - 200 + index,
                AppEventLevel.Warn,
                "command=setSubscriptionUserAgent phase=complete outcome=superseded code=IncompatibleCore elapsedMs=${9_000_000_000_000_000_000L + index}",
            )
        }
        val longModel = app.copy(model = "界".repeat(64))
        val url = link(events, longModel)
        assertTrue(url.length < 8000)
        val body = query(URI(url)).getValue("body")
        val included = indices(body)
        assertTrue(included.size in 1..49)
        assertEquals((200 - included.size until 200).toList(), included)
        val nextLine = query(URI(link(events.take(200 - included.size), longModel)))
            .getValue("body").lineSequence().last { it.contains("elapsedMs=") } + "\n"
        assertTrue(url.length + URLEncoder.encode(nextLine, Charsets.UTF_8.name()).length >= 8000)
    }

    @Test
    fun `the draft never includes untrusted command version or event text`() {
        val sub = "https://subscription.example/sub?token=fixture-token"
        val credential = "fixture-user:fixture-password"
        val events = listOf(event(1), event(2).copy(text = "token=fixture-token $credential $sub"))
        val url = GitHubIssueLink.build(ErrorCode.Internal, credential, at, app, sub, events)
        val query = query(URI(url))
        val body = query.getValue("body")
        assertEquals(listOf(1), indices(body))
        for (secret in listOf(sub, "subscription.example", "fixture-token", "fixture-user", "fixture-password")) {
            assertFalse(secret, query.values.any { secret in it })
        }
    }

    private fun link(events: List<AppEvent>, info: AppInfo = app) =
        GitHubIssueLink.build(ErrorCode.Internal, "start", at, info, "0.2.0", events)

    private fun event(index: Int) = AppEvent(
        at.toEpochMilli() + index,
        AppEventLevel.Warn,
        "command=start phase=complete outcome=failed code=Internal elapsedMs=$index",
    )

    private fun indices(body: String) = Regex("elapsedMs=(\\d+)").findAll(body).map { it.groupValues[1].takeLast(3).toInt() }.toList()

    private fun query(uri: URI) = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8.name()) }
}
