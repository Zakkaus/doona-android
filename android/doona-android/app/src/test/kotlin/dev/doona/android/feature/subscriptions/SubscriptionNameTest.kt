package dev.doona.android.feature.subscriptions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A subscription's name as the fields take it: the core's 64 code points, on one line (`Engine.renameSubscription`). */
class SubscriptionNameTest {
    @Test
    fun `a name of 64 code points is taken and one more is refused, a surrogate pair counting as one`() {
        val duck = "🦆"
        assertTrue(rvNameValid("a".repeat(64)))
        assertFalse(rvNameValid("a".repeat(65)))
        assertTrue(rvNameValid(duck.repeat(64)))
        assertFalse(rvNameValid(duck.repeat(65)))
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
