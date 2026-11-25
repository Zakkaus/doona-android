package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The footnote check itself ([rvFootnoteRules], run on every golden): [RvFootnote] under a card is the card's text
 * column on both sides, in a page's gutter and in a settings column that insets the cards itself; a footnote-tagged
 * text set on the gutter (16 dp) is caught, and so is a box that starts at the column but runs to the card's end (the
 * 32..396 of 811cda31, owner q-s1125c-footgap 19:05 「我感覺向右歪了」). The tokens themselves are the column. A footnote
 * that needs more than its three lines reads as cut.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvFootnoteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFootnoteUnderItsCardKeepsToTheTokens() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    RvGutter { RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) } }
                    RvFootnote(NOTE)
                    Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
                        RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) }
                        RvFootnote(NOTE, inset = RvSpace.none)
                    }
                }
            }
        }
        compose.waitForIdle()
        val read = rvFootnotes(compose.rvRowNodes())
        assertEquals("both footnotes read", 2, read.size)
        read.forEach { assertTrue("${it.line("test")} keeps to the tokens", !it.off) }
    }

    @Test
    fun aFootnoteOnTheGutterIsCaught() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    RvGutter { RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) } }
                    // A page-local footnote on the gutter, as pages drew them before RvFootnote: 16 dp in, to 16 dp from the edge.
                    RvText(NOTE, Rv.type.rowSub, Modifier.padding(horizontal = RvSpace.gutter).fillMaxWidth().testTag(RvPart.FOOTER), color = Rv.colors.textSecondary)
                }
            }
        }
        compose.waitForIdle()
        assertTrue("the gutter footnote is off", rvFootnotes(compose.rvRowNodes()).single().off)
    }

    @Test
    fun aBoxThatRunsToTheCardsEndIsCaught() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    RvGutter { RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) } }
                    // 811cda31's footnote: in by the card's padding at the start, at the card's edge at the end.
                    val box = Modifier.padding(start = RvSpace.gutter + RvSpace.rowPadding, end = RvSpace.gutter).fillMaxWidth().testTag(RvPart.FOOTER)
                    RvText(NOTE, Rv.type.rowSub, box, color = Rv.colors.textSecondary)
                }
            }
        }
        compose.waitForIdle()
        val read = rvFootnotes(compose.rvRowNodes()).single()
        assertTrue("${read.line("test")}: the asymmetric box is off", read.off)
    }

    @Test
    fun theTokensAreTheCardsTextColumnOnBothSides() {
        assertEquals("footnote start = the card's text inset", RvSpace.rowPadding, RvSpace.footnoteStart)
        assertEquals("footnote end = the card's text inset (q-s1125c-footgap 19:05)", RvSpace.rowPadding, RvSpace.footnoteEnd)
    }

    /** A footnote that needs more than its three lines is cut, and the check says so; a [RvFootnote] `whole` is not. */
    @Test
    fun aFootnotePastItsThreeLinesIsCaughtAsCut() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    RvGutter { RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) } }
                    RvFootnote(LONG_NOTE)
                    RvGutter { RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) } }
                    RvFootnote(LONG_NOTE, whole = true)
                    RvGutter { RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) } }
                    RvFootnote(NOTE)
                }
            }
        }
        compose.waitForIdle()
        assertEquals("cut, whole, short", listOf(true, false, false), rvFootnotes(compose.rvRowNodes()).map { it.cut })
    }

    private companion object {
        const val CARD = "卡片"
        const val NOTE = "這些選項可能變動或移除。"
        val LONG_NOTE = List(8) { NOTE }.joinToString("")
    }
}
