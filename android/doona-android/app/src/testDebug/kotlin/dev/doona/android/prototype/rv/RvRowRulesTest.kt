package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The row rules gate itself ([rvRowRules]): it reads a row the library draws and catches a disc moved by 2 dp, and its
 * allowlist names only goldens that exist, each line once and in order, so a stale line cannot hide.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvRowRulesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aDiscTwoDpOffTheRowsCentreBreaksR8() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, KEPT, null)
                    // The disc in a box 4 dp taller at its top: centred, the disc sits 2 dp low.
                    RvRow({ Box(Modifier.padding(top = MOVE.dp)) { RvIconDisc(R.drawable.rv_sym_rule) } }, MOVED, null)
                }
            }
        }
        compose.waitForIdle()
        val rows = rvRowsOf(compose.rvRowNodes())
        assertEquals("the rows read", listOf(KEPT, MOVED), rows.map { it.title.text })
        assertEquals(listOf(RvRowBreak("R8", MOVED, "+2.0")), rvRowBreaks(rows))
    }

    @Test
    fun aDiscOneDpOffTheRowsCentreKeepsToTheTolerance() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                // The disc in a box 2 dp taller at its top: centred, the disc sits 1 dp low.
                RvRow({ Box(Modifier.padding(top = 2.dp)) { RvIconDisc(R.drawable.rv_sym_rule) } }, MOVED, null)
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<RvRowBreak>(), rvRowBreaks(rvRowsOf(compose.rvRowNodes())))
    }

    @Test
    fun theAllowlistNamesGoldensThatExistOnceAndInOrder() {
        val lines = File(RV_ROW_RULES_ALLOWLIST).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        lines.forEach { line ->
            val fields = line.split('\t')
            assertEquals("four tab-separated fields: $line", FIELDS, fields.size)
            assertTrue("no golden ${fields[0]}.png for: $line", File("$SHOTS/${fields[0]}.png").exists())
        }
        assertEquals("the allowlist's lines, sorted and once each", lines.distinct().sorted(), lines)
    }

    private companion object {
        const val KEPT = "Kept"
        const val MOVED = "Moved"
        const val MOVE = 4
        const val FIELDS = 4
        const val SHOTS = "src/test/screenshots"
    }
}
