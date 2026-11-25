package dev.doona.android.designsystem.rv

import dev.doona.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The core's bounds as 新增訂閱 states them, in binary units with no break before the unit. */
class RvMebibytesTest {
    private val format = RvFormat(Locale.US)

    @Test
    fun `a bound in whole or half mebibytes keeps no trailing zeros`() {
        assertEquals("8 MiB", format.mebibytes(8L shl 20).toString())
        assertEquals("0.5 MiB", format.mebibytes(1L shl 19).toString())
        assertEquals("8 MiB", format.mebibytes(8L shl 20).unbroken())
    }

    @Test
    fun `the download bound comes from the core's file`() {
        assertEquals("8 MiB", format.mebibytes(BuildConfig.SUBSCRIPTION_MAX_BODY_BYTES).toString())
    }
}
