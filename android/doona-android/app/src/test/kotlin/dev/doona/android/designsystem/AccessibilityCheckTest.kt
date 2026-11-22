package dev.doona.android.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaBarAction
import dev.doona.android.designsystem.components.DoonaChartTools
import dev.doona.android.designsystem.components.DoonaGlyph
import dev.doona.android.designsystem.components.DoonaIconAction
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.components.DoonaToolDisc
import dev.doona.android.designsystem.components.GalleryPage
import dev.doona.android.designsystem.components.Section
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.checkAccessibility
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.doona.android.icons.R as IconsR

/**
 * The accessibility check of the screenshot tests must fail on what it is there for. An action with no name is the
 * defect it exists to catch: the check passes the labelled action and fails the unlabelled one, an icon action and the
 * chart tools and tool disc alike (ATF alone passes the last two; see [checkAccessibility]).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class AccessibilityCheckTest {
    @get:Rule
    val compose = createComposeRule()

    private fun check(label: String): Throwable? = check { DoonaIconAction(IconsR.drawable.sym_search, label, onClick = {}) }

    private fun check(content: @Composable () -> Unit): Throwable? {
        compose.setDoonaContent(content)
        compose.drawFully()
        return runCatching { compose.onRoot().checkAccessibility() }.exceptionOrNull()
    }

    private fun chartTools(label: String) = check {
        DoonaChartTools(DoonaBarAction(IconsR.drawable.sym_refresh, label, onClick = {}), DoonaBarAction(IconsR.drawable.sym_share, "分享", onClick = {}))
    }

    @Test
    fun aLabelledIconActionPasses() {
        val failure = check("Search")
        assertNull(failure?.toString(), failure)
    }

    @Test
    fun anUnlabelledIconActionFails() = assertNotNull("the check passed an icon action that a screen reader cannot name", check(""))

    @Test
    fun labelledChartToolsPass() {
        val failure = chartTools("重新測速")
        assertNull(failure?.toString(), failure)
    }

    @Test
    fun anUnlabelledChartToolFails() = assertNotNull("the check passed a chart tool that a screen reader cannot name", chartTools(""))

    @Test
    fun anUnlabelledToolDiscFails() = assertNotNull(
        "the check passed a tool disc that a screen reader cannot name",
        check { DoonaToolDisc(IconsR.drawable.sym_refresh, "", onClick = {}) },
    )

    /**
     * A gallery page of the design system's controls with one more tool disc: named, or a bare glyph that only clicks, which
     * no screen reader can name.
     */
    private fun checkGallery(labelled: Boolean): Throwable? {
        compose.setDoonaContent {
            GalleryPage("RV controls") {
                Section("DoonaToolDisc") {
                    if (labelled) {
                        DoonaToolDisc(IconsR.drawable.sym_refresh, "Refresh", onClick = {})
                    } else {
                        Box(Modifier.minimumInteractiveComponentSize().clickable {}) { DoonaGlyph(IconsR.drawable.sym_refresh, DoonaTheme.chart.roundGlyph, DoonaTheme.colors.text) }
                    }
                }
                Section("DoonaSegmented") { DoonaSegmented(listOf("規則", "全域"), selected = 0, onSelect = {}) }
            }
        }
        compose.drawFully()
        return runCatching { compose.onRoot().checkAccessibility() }.exceptionOrNull()
    }

    @Test
    fun aGalleryPageWithALabelledToolDiscPasses() {
        val failure = checkGallery(labelled = true)
        assertNull(failure?.toString(), failure)
    }

    @Test
    fun anUnlabelledControlOnAGalleryPageFails() = assertNotNull("the check passed a gallery control that a screen reader cannot name", checkGallery(labelled = false))
}
