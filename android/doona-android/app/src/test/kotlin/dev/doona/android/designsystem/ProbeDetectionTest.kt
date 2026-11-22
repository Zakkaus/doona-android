package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The layout probe reads Compose internals (the child list of a layout node, the class name of its measure policy and of
 * its modifiers). This page holds one of each thing the probe must find, so a Compose release that renames one of them
 * fails here, by name, and not by every spacing rule passing over a tree that reads empty.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class ProbeDetectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theProbeFindsColumnsRowsFillsGlyphsAndRoles() {
        compose.setDoonaContent {
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Box(Modifier.size(20.dp).background(Color.Red))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(20.dp).background(Color.Blue))
                }
                Spacer(Modifier.height(12.dp))
                DoonaSegmented(listOf("A", "B"), selected = 0, onSelect = {})
                DoonaIcon(R.drawable.ic_search, DoonaTheme.colors.accent)
                DoonaText("hello", DoonaTheme.type.body)
            }
        }
        val tree = compose.layoutTree()
        val nodes = tree.descendants().toList()

        val column = nodes.first { it.vertical && it.children.size == 5 }
        val gaps = column.columnGaps()
        assertEquals("the column's gaps, blank spacer left out: ${column.content}", 3, gaps.size)
        assertEquals(12f, gaps.first().third, 0.5f)

        val row = nodes.first { !it.vertical && it.children.size == 3 }
        assertEquals(listOf(8f), row.rowGaps().map { it.third })
        assertTrue("a spacer is blank", row.children[1].blank)
        assertTrue("a box with a background is filled and painted", row.children[0].let { it.filled && it.painted })

        assertTrue("a segmented control is a filled control", nodes.any { it.role == LayoutRole.Control && it.descendants().any { d -> d.filled } })
        assertTrue("an icon is a glyph", nodes.any { it.glyph })
        assertTrue("text is read", nodes.any { it.text == "hello" })
        assertEquals(emptyList<String>(), SpacingRules.detected(tree, setOf(LayoutRole.Control)))
        assertEquals("a role the page lacks is reported", 1, SpacingRules.detected(tree, setOf(LayoutRole.Sheet)).size)
    }

    /** A tree with no column and nothing painted is not a page: reading it fails with the list of what was not found. */
    @Test
    fun anEmptyTreeIsNotReadAsAPageThatKeepsEveryRule() {
        compose.setDoonaContent { Box(Modifier.fillMaxSize()) }
        try {
            compose.layoutTree()
            fail("the probe read a tree without a column, a fill or text and found nothing wrong with it")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, "ColumnMeasurePolicy" in e.message.orEmpty() && "BackgroundElement" in e.message.orEmpty())
        }
    }
}
