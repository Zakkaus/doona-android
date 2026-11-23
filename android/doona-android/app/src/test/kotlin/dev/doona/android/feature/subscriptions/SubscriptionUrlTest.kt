package dev.doona.android.feature.subscriptions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionUrlTest {
    @Test
    fun `an http or https address with a host passes`() {
        listOf("https://example.invalid/sub", "http://example.invalid", "HTTPS://Example.invalid/a?token=1", "https://10.0.0.1:8443/x")
            .forEach { assertNull(it, urlProblem(it)) }
    }

    @Test
    fun `a name with characters beyond ASCII is left to the core`() {
        listOf("https://bücher.example/sub", "https://例え.jp/a?t=1", "http://xn--bcher-kva.example/").forEach { assertNull(it, urlProblem(it)) }
    }

    @Test
    fun `a pasted trailing space or newline is ignored`() {
        assertNull(urlProblem("  https://example.invalid/sub\n"))
    }

    @Test
    fun `nothing or only blanks is empty`() {
        assertEquals(UrlProblem.Empty, urlProblem(""))
        assertEquals(UrlProblem.Empty, urlProblem("   \n"))
    }

    @Test
    fun `another scheme, no scheme or no host is invalid`() {
        listOf("ftp://example.invalid/sub", "example.invalid/sub", "vmess://abc", "https://", "https:///path", "not a url", "https://exa mple.invalid")
            .forEach { assertEquals(it, UrlProblem.Invalid, urlProblem(it)) }
    }
}
