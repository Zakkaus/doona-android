package dev.doona.android.engine.fake

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fake keeps subscriptions as the core does: agents under the core's rule, and ids that are never given twice. */
class FakeEngineSubscriptionsTest {
    private fun Result<*>.code() = (exceptionOrNull() as EngineException).code

    /** CR, LF, NUL and other controls, non-ASCII, more than 256 bytes and blank values, as `fetch::user_agent` refuses them. */
    private val refused = listOf("", " ", "   ", "x".repeat(257), "doona\r\nX-Injected: 1", "doona\n", "doona\u0000", "doona\t1", "doona\u007f", "café")

    @Test
    fun anAgentTheCoreRefusesIsInvalidAndChangesNothing() = runTest {
        val engine = FakeEngine()
        val before = engine.subscriptions.value
        for (agent in refused) {
            assertEquals(agent, ErrorCode.InvalidArgument, engine.addSubscription("https://example.invalid/new", userAgent = agent).code())
            assertEquals(agent, ErrorCode.InvalidArgument, engine.setSubscriptionUserAgent("s1", agent).code())
        }
        assertEquals(before, engine.subscriptions.value)
    }

    @Test
    fun anAgentIsKeptTrimmedAndNullReturnsToTheDefault() = runTest {
        val engine = FakeEngine()
        val added = engine.addSubscription("https://example.invalid/new", userAgent = "  clash-verge/v2.2.3 ").getOrThrow()
        assertEquals("clash-verge/v2.2.3", added.userAgent)
        assertEquals("x".repeat(256), engine.setSubscriptionUserAgent(added.id, "x".repeat(256)).getOrThrow().userAgent)
        assertNull(engine.setSubscriptionUserAgent(added.id, null).getOrThrow().userAgent)
        assertEquals(ErrorCode.NotFound, engine.setSubscriptionUserAgent("s9", "mihomo/1.19.16").code())
    }

    @Test
    fun idsAreNeverGivenTwiceAndSettingOneLeavesTheOthers() = runTest {
        val engine = FakeEngine()
        val second = engine.addSubscription("https://example.invalid/second").getOrThrow()
        engine.removeSubscription("s1").getOrThrow()
        val third = engine.addSubscription("https://example.invalid/third").getOrThrow()
        assertEquals(listOf("s2", "s3"), listOf(second.id, third.id))
        // A list a test sets directly is avoided too.
        engine.subscriptions.value = engine.subscriptions.value + second.copy(id = "s7")
        assertEquals("s8", engine.addSubscription("https://example.invalid/eighth").getOrThrow().id)

        // With an id given twice, both would change.
        val untouched = engine.subscriptions.value.filter { it.id != "s2" }
        assertEquals("mihomo/1.19.16", engine.setSubscriptionUserAgent("s2", "mihomo/1.19.16").getOrThrow().userAgent)
        assertEquals(untouched, engine.subscriptions.value.filter { it.id != "s2" })
        assertEquals("mihomo/1.19.16", engine.subscriptions.value.single { it.id == "s2" }.userAgent)
    }
}
