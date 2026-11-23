package dev.doona.android.prototype.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.testing.GestureBar
import dev.doona.android.testing.StatusBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The existing pages in windows other than the 412 dp phone, recorded as they are, light and dark, first screen only:
 * 活動, 策略 and 設定 at 840 dp wide (`proto-rv-wide-activity-*`, `-wide-policy-*`, `-wide-settings-*`), 活動 under a
 * display cutout (`-cutout-activity-*`) and with three-button navigation (`-threebutton-activity-*`). The bars are the
 * test's stand-ins: a notch phone's 48 dp status bar with a notch drawn in it, and a 48 dp navigation bar with its
 * three buttons; the pages read them as insets, as they read the frame's everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvWindowShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WIDE)
    fun wideActivityLight() = compose.rvShot("wide-activity", dark = false) { Activity() }

    @Test
    @Config(qualifiers = WIDE)
    fun wideActivityDark() = compose.rvShot("wide-activity", dark = true) { Activity() }

    @Test
    @Config(qualifiers = WIDE)
    fun widePolicyLight() = compose.rvShot("wide-policy", dark = false) { Policy() }

    @Test
    @Config(qualifiers = WIDE)
    fun widePolicyDark() = compose.rvShot("wide-policy", dark = true) { Policy() }

    @Test
    @Config(qualifiers = WIDE)
    fun wideSettingsLight() = compose.rvShot("wide-settings", dark = false) { Settings() }

    @Test
    @Config(qualifiers = WIDE)
    fun wideSettingsDark() = compose.rvShot("wide-settings", dark = true) { Settings() }

    @Test fun cutoutActivityLight() = compose.rvShot("cutout-activity", dark = false) { Cutout { Activity() } }

    @Test fun cutoutActivityDark() = compose.rvShot("cutout-activity", dark = true) { Cutout { Activity() } }

    @Test fun threeButtonActivityLight() = compose.rvShot("threebutton-activity", dark = false, gesture = false) { ThreeButton { Activity() } }

    @Test fun threeButtonActivityDark() = compose.rvShot("threebutton-activity", dark = true, gesture = false) { ThreeButton { Activity() } }

    @Composable
    private fun Activity() = RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {})

    @Composable
    private fun Policy() = RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Rule), onChange = {})

    @Composable
    private fun Settings() = RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {})

    /** A notch phone: the status bar is as tall as the notch, which the page reads as its top inset. */
    @Composable
    private fun Cutout(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalFrameInsets provides WindowInsets(top = CUTOUT_BAR, bottom = GestureBar)) { content() }
            Box(
                Modifier.align(Alignment.TopCenter).size(NOTCH_WIDTH, NOTCH_HEIGHT).background(Color.Black, RoundedCornerShape(bottomStart = NOTCH_HEIGHT / 2, bottomEnd = NOTCH_HEIGHT / 2)),
            )
        }
    }

    /** Three-button navigation: a bar of its height at the bottom, with back, home and recents, which the page reads as its bottom inset. */
    @Composable
    private fun ThreeButton(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalFrameInsets provides WindowInsets(top = StatusBar, bottom = NAV_BAR)) { content() }
            val ink = Rv.colors.text
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(NAV_BAR).background(Rv.colors.page)) {
                val mid = size.height / 2
                val half = BUTTON.toPx() / 2
                val line = Stroke(BUTTON_LINE.toPx())
                val at = { share: Float -> size.width * share }
                // Back: a triangle pointing left; home: a ring; recents: a rounded square.
                drawPath(
                    Path().apply {
                        moveTo(at(BACK) + half, mid - half)
                        lineTo(at(BACK) - half, mid)
                        lineTo(at(BACK) + half, mid + half)
                        close()
                    },
                    ink,
                    style = line,
                )
                drawCircle(ink, half, Offset(at(HOME), mid), style = line)
                drawRoundRect(ink, Offset(at(RECENTS) - half, mid - half), Size(half * 2, half * 2), androidx.compose.ui.geometry.CornerRadius(BUTTON_CORNER.toPx()), style = line)
            }
        }
    }

    private companion object {
        /** The 840 dp window of the old shell's `*-wide` goldens, as tall as the phone. */
        const val WIDE = "zh-rTW-w840dp-h915dp-notnight-xhdpi"

        val CUTOUT_BAR = 48.dp
        val NOTCH_WIDTH = 154.dp
        val NOTCH_HEIGHT = 30.dp
        val NAV_BAR = 48.dp
        val BUTTON = 16.dp
        val BUTTON_LINE = 2.dp
        val BUTTON_CORNER = 3.dp
        const val BACK = 0.25f
        const val HOME = 0.5f
        const val RECENTS = 0.75f
    }
}
