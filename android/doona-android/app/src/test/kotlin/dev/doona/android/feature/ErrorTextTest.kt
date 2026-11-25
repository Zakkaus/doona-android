package dev.doona.android.feature

import dev.doona.android.engine.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTextTest {
    /** A failure banner says why with [reasonRes]: no two codes share a line, so no reason hides another. */
    @Test
    fun `every error code has its own reason line`() {
        val reasons = ErrorCode.entries.map { it.reasonRes() }
        assertEquals(ErrorCode.entries.size, reasons.toSet().size)
    }
}
