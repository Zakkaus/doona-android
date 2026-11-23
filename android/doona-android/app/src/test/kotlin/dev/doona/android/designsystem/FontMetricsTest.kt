package dev.doona.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The Noto Sans TC subset keeps the metrics every golden was drawn with. A rebuild (`tools/fonts.py`) may change which
 * characters the files hold, nothing else: Android places a line of text by these fields, so a bounding box widened by a
 * new character moves the text in every row of the app. `tools/font_metrics.py` compares two builds field by field.
 */
class FontMetricsTest {
    /** One weight's fields, as the files the goldens of 083d18c were drawn with hold them. */
    private data class Metrics(
        val bounds: List<Int>,
        val unitsPerEm: Int = 1000,
        val revision: Int = REVISION,
        val hhea: List<Int> = listOf(1000, -200, 0),
        val typo: List<Int> = listOf(1000, -200, 0),
        val win: List<Int> = listOf(1000, 200),
        val fsSelection: Int = 192,
    )

    private val expected = mapOf(
        400 to Metrics(bounds = listOf(-44, -279, 2928, 880)),
        500 to Metrics(bounds = listOf(-48, -281, 2927, 894)),
        600 to Metrics(bounds = listOf(-51, -282, 2927, 909)),
        700 to Metrics(bounds = listOf(-54, -284, 2926, 924)),
        800 to Metrics(bounds = listOf(-57, -285, 2926, 938)),
    )

    @Test
    fun `every weight keeps the metrics the goldens were drawn with`() {
        for ((weight, want) in expected) {
            val got = read(File("src/main/res/font/noto_sans_tc_w$weight.ttf"))
            assertEquals("weight $weight", want, got)
        }
    }

    /** The fields of a TrueType file, from its table directory (OpenType spec: `head`, `hhea`, `OS/2`). */
    private fun read(file: File): Metrics {
        val bytes = ByteBuffer.wrap(file.readBytes())
        val tables = (0 until bytes.getShort(4).toInt()).associate { i ->
            val record = 12 + 16 * i
            String(ByteArray(4) { bytes.get(record + it) }, Charsets.US_ASCII) to bytes.getInt(record + 8)
        }
        val head = tables.getValue("head")
        val hhea = tables.getValue("hhea")
        val os2 = tables.getValue("OS/2")
        fun short(at: Int) = bytes.getShort(at).toInt()
        fun ushort(at: Int) = bytes.getShort(at).toInt() and 0xFFFF
        return Metrics(
            bounds = listOf(short(head + 36), short(head + 38), short(head + 40), short(head + 42)),
            unitsPerEm = ushort(head + 18),
            revision = bytes.getInt(head + 4),
            hhea = listOf(short(hhea + 4), short(hhea + 6), short(hhea + 8)),
            typo = listOf(short(os2 + 68), short(os2 + 70), short(os2 + 72)),
            win = listOf(ushort(os2 + 74), ushort(os2 + 76)),
            fsSelection = ushort(os2 + 62),
        )
    }

    private companion object {
        /** Noto Sans TC 2.004 (`@fontsource-variable/noto-sans-tc` 5.3.0), as a 16.16 fixed-point number. */
        const val REVISION = 0x00020106
    }
}
