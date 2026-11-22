package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.seedColorScheme
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.drawFully
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * The Revolut-style 節點 page and a node's page, light and dark, in the scheme of the owner's wallpaper. The 節點 page is
 * one long capture of the whole page (`proto-rv-nodes-page-light` and `-dark`); the node's page, sg-03 slowing down,
 * is one screen with its actions floating at the bottom (`proto-rv-node-detail-light` and `-dark`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodesShotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "src/test/screenshots"
    private val zone = TimeZone.getDefault()

    // The tests' times print in the device's zone; UTC keeps the goldens the same on every machine.
    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageLight() = shot("nodes-page", dark = false) { Page() }

    @Test
    @Config(qualifiers = WHOLE_PAGE)
    fun pageDark() = shot("nodes-page", dark = true) { Page() }

    @Test
    fun detailLight() = shot("node-detail", dark = false) { Detail() }

    @Test
    fun detailDark() = shot("node-detail", dark = true) { Detail() }

    @Composable
    private fun Page() = RvNodesPage(sampleNodesPage(), onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {})

    @Composable
    private fun Detail() {
        val page = sampleNodesPage()
        val node = page.nodes.first { it.id == RV_NODES_DETAIL }
        RvNodeDetail(node, current = node.id == page.currentId, page.testedAtEpochMs, onBack = {}, onUse = {}, onTest = {})
    }

    private fun shot(screen: String, dark: Boolean, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.setContent {
            DoonaTheme(dark) {
                PhoneFrame {
                    RvTheme(seedColorScheme(Seed.Cyan.argb, dark), dark, content)
                }
            }
        }
        compose.drawFully()
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$dir/proto-rv-$screen-${if (dark) "dark" else "light"}.png")
    }

    private companion object {
        /** A window as tall as the whole 節點 page, so one capture holds every card. */
        const val WHOLE_PAGE = "zh-rTW-w412dp-h2868dp-notnight-xhdpi"
    }
}
