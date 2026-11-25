package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.RvWhole
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
                    // Rows at the window's edge go on past it and are not measured, so the rows stand clear of both edges.
                    Spacer(Modifier.height(EDGE.dp))
                    RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, KEPT, null)
                    // The disc in a box 4 dp taller at its top: centred, the disc sits 2 dp low.
                    RvRow({ Box(Modifier.padding(top = MOVE.dp)) { RvIconDisc(R.drawable.rv_sym_rule) } }, MOVED, null)
                    Spacer(Modifier.height(EDGE.dp))
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
                Column {
                    Spacer(Modifier.height(EDGE.dp))
                    RvRow({ Box(Modifier.padding(top = 2.dp)) { RvIconDisc(R.drawable.rv_sym_rule) } }, MOVED, null)
                    Spacer(Modifier.height(EDGE.dp))
                }
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<RvRowBreak>(), rvRowBreaks(rvRowsOf(compose.rvRowNodes())))
    }

    @Test
    fun aRowsSubtitleStandsTwoDpUnderItsTitle() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    Spacer(Modifier.height(EDGE.dp))
                    RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, KEPT, SUBTITLE)
                    Spacer(Modifier.height(EDGE.dp))
                }
            }
        }
        compose.waitForIdle()
        val rows = rvRowsOf(compose.rvRowNodes())
        val row = rows.single()
        assertEquals("title to subtitle, dp (q-s1125c-titlegap)", 2f, row.subtitles.single().box.top - row.title.box.bottom, HALF)
        assertEquals(emptyList<RvRowBreak>(), rvRowBreaks(rows).filter { it.rule == "R12" })
    }

    @Test
    fun aSubtitleTouchingItsTitleBreaksR12() {
        // The rows before q-s1125c-titlegap: the subtitle's line box started where the title's ended.
        val title = RvNode(-1, Rect(32f, 119f, 380f, 142f), null, KEPT, RvFirstLine(0f, 23f, 1))
        val subtitle = RvNode(-1, Rect(32f, 142f, 380f, 162.5f), null, SUBTITLE, RvFirstLine(0f, 20.5f, 1))
        val row = RvRowRead(RvRowKind.List, Rect(16f, 100f, 396f, 182f), null, title, listOf(subtitle), null, emptySet(), null)
        assertEquals(listOf(RvRowBreak("R12", KEPT, "-2.0")), rvRowBreaks(listOf(row)).filter { it.rule == "R12" })
    }

    /**
     * A text with no line limit is not an exception by that alone: the library tags it whole only through an exception
     * of LAYOUT-RULES.md (RvWhole), so a title of [Int.MAX_VALUE] lines and no exception is held to R14's two lines.
     */
    @Test
    fun aTitleWithNoLineLimitAndNoExceptionBreaksR14() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    Spacer(Modifier.height(EDGE.dp))
                    RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, LONG, null, titleLines = Int.MAX_VALUE)
                    Spacer(Modifier.height(EDGE.dp))
                }
            }
        }
        compose.waitForIdle()
        val rows = rvRowsOf(compose.rvRowNodes())
        assertTrue("the title takes more than two lines", (rows.single().title.line?.lines ?: 0) > 2)
        assertEquals(listOf("R14"), rvRowBreaks(rows).filter { it.rule == "R14" }.map { it.rule })
    }

    /** The same title shown whole by a listed exception keeps every line and breaks nothing. */
    @Test
    fun aTitleShownWholeByAListedExceptionKeepsToR14() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    Spacer(Modifier.height(EDGE.dp))
                    RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, LONG, null, titleLines = Int.MAX_VALUE, whole = RvWhole.RevisionName)
                    Spacer(Modifier.height(EDGE.dp))
                }
            }
        }
        compose.waitForIdle()
        val rows = rvRowsOf(compose.rvRowNodes())
        assertTrue("the title takes more than two lines", (rows.single().title.line?.lines ?: 0) > 2)
        assertEquals(emptyList<String>(), rvRowBreaks(rows).filter { it.rule == "R14" }.map { it.rule })
    }

    /** A whole text whose exception LAYOUT-RULES.md does not list breaks R14. */
    @Test
    fun aWholeTextWithAnUnlistedExceptionBreaksR14() {
        val title = RvNode(-1, Rect(32f, 116f, 380f, 185f), null, KEPT, RvFirstLine(0f, 23f, 3))
        val row = RvRowRead(RvRowKind.List, Rect(16f, 100f, 396f, 201f), null, title, emptyList(), null, emptySet(), null, whole = emptySet(), unlisted = listOf("q-none"))
        assertEquals(listOf("R14"), rvRowBreaks(listOf(row)).filter { it.rule == "R14" }.map { it.rule })
    }

    @Test
    fun everyWholeExceptionIsARowOfLayoutRules() {
        assertTrue("LAYOUT-RULES.md's Exceptions read", RV_LAYOUT_EXCEPTIONS.isNotEmpty())
        RvWhole.entries.forEach { assertTrue("${it.id} is a row of LAYOUT-RULES.md's Exceptions", it.id in RV_LAYOUT_EXCEPTIONS) }
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
        const val LONG = "這一列的標題非常長，長到在一般字級下也要換成三行以上，才放得下它全部的文字內容與後面補充的說明"
        const val SUBTITLE = "Subtitle"
        const val HALF = 0.5f
        const val MOVED = "Moved"
        const val MOVE = 4
        const val EDGE = 10
        const val FIELDS = 4
        const val SHOTS = "src/test/screenshots"
    }
}
