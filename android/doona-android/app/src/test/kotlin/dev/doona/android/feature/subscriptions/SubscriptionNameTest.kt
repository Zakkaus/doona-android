package dev.doona.android.feature.subscriptions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A subscription's name as the fields take it: the core's 64 code points, on one line (`Engine.renameSubscription`). */
class SubscriptionNameTest {
    @Test
    fun `the field takes 64 code points and no more, counting a surrogate pair as one`() {
        val duck = "🦆"
        assertEquals("a".repeat(64), rvNameTyped("a".repeat(70)))
        assertEquals(duck.repeat(64), rvNameTyped(duck.repeat(65)))
        assertEquals("Harbor Cloud", rvNameTyped("Harbor Cloud"))
    }

    @Test
    fun `a name with a control or a line break is refused, as the core refuses it`() {
        assertTrue(rvNameValid("Harbor Cloud"))
        assertTrue(rvNameValid("港灣雲 🦆"))
        assertFalse(rvNameValid("Harbor\nCloud"))
        assertFalse(rvNameValid("Harbor\tCloud"))
        assertFalse(rvNameValid("Harbor Cloud"))
        assertFalse(rvNameValid("Harbor Cloud"))
        assertFalse(rvNameValid("a".repeat(65)))
    }
}
