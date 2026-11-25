package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.doona.android.designsystem.rv.RvMaterialPlumbing
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.drawFully
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import java.util.TimeZone
import kotlin.math.roundToInt

private val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"

/**
 * Draws [content] in the scheme of the owner's wallpaper, light or [dark], runs [before] (a scroll, an assertion) and
 * records the golden `proto-rv-<screen>-light` or `-dark`. At a [fontScale] other than 1 the page's text scales as the
 * phone's font size setting scales it (the frame's status bar does not), and the golden is
 * `proto-rv-<screen>-fs<percent>`, as the app's `fs115` and `fs200`. A frame with [gesture] false draws no gesture handle
 * (the content draws its own bar, as three-button navigation does). With `doona.board` set the page is captured for the
 * design board ([rvBoard]) instead of the golden. A wired page's golden takes [prefix] `app-rv` (plan §2.4): the same
 * page drawn from the engine through its view model, set against the `proto-rv` golden of the same screen. A golden of
 * the phone in English ([RV_EN]) is `<prefix>-<screen>-en`. With [windows] every window is captured, so a sheet's
 * dialog or a menu's popup open over the page is in the golden; the page's own window alone is captured otherwise.
 */
internal fun ComposeContentTestRule.rvShot(
    screen: String,
    dark: Boolean,
    before: ComposeContentTestRule.() -> Unit = {},
    fontScale: Float = 1f,
    gesture: Boolean = true,
    prefix: String = PROTO,
    english: Boolean = false,
    windows: Boolean = false,
    content: @Composable () -> Unit,
) {
    rvContent(dark, fontScale, gesture, content)
    before()
    // An open sheet or menu is a second root: the helpers below read one root, so they wait for the windows instead.
    if (windows) waitForIdle() else drawFully()
    val variant = when {
        fontScale != 1f -> "fs${(fontScale * PERCENT).roundToInt()}"
        english -> "en"
        dark -> "dark"
        else -> "light"
    }
    // The board's run draws the board, not the goldens: its windows are held to the phone's height.
    if (rvBoarding) return rvBoard(screen, variant)
    // Every golden's rows keep to the layout rules (docs/LAYOUT-RULES.md), or to the allowlist of today's breaks.
    // The row and footnote rules read the page's own window; with [windows] that page is the one its golden without the
    // sheet checks.
    if (!windows) {
        rvRowRules("$prefix-$screen-$variant")
        // Every footnote stands where RvSpace.footnoteStart and footnoteEnd put it (LAYOUT-RULES.md R20).
        rvFootnoteRules("$prefix-$screen-$variant")
    }
    val path = "$SHOTS/$prefix-$screen-$variant.png"
    if (windows) captureScreenRoboImage(path) else onRoot().captureToImage().asAndroidBitmap().captureRoboImage(path)
}

/**
 * Sets [content] in the phone's frame, in the owner's wallpaper scheme, light or [dark], its text at [fontScale]; with
 * [gesture] false the frame draws no gesture handle.
 */
internal fun ComposeContentTestRule.rvContent(dark: Boolean, fontScale: Float = 1f, gesture: Boolean = true, content: @Composable () -> Unit) {
    RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
    setContent {
        RvMaterialPlumbing(rvSystemScheme(dark)) {
            RvBoardWindow {
                PhoneFrame(gesture) {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        RvTheme(seedColorScheme(Seed.Cyan.argb, dark), dark, content)
                    }
                }
            }
        }
    }
}

private const val PERCENT = 100

/** The prototype's goldens. */
private const val PROTO = "proto-rv"

/** Android's two font sizes beyond the default that the goldens cover. */
internal const val RV_FS115 = 1.15f
internal const val RV_FS200 = 2f

/** The phone in English, for the goldens that show the English labels in their slots (wording review, Addendum 4 C). */
internal const val RV_EN = "en-w412dp-h915dp-notnight-xhdpi"

/** Scrolls the page's content up by [dp], as a finger would, and lets what follows the scroll (the nav's collapse) settle. */
internal fun ComposeContentTestRule.rvScrollBy(dp: Float) {
    onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dp * density.density) }
    waitForIdle()
}

/** A full capture holds the whole page: its window is as tall as the page, so nothing is left to scroll. */
internal fun ComposeContentTestRule.assertWhole() {
    // The board holds every window to the phone's height, so a whole page scrolls there.
    if (rvBoarding) return
    val page = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()
    val left = page.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() / density.density
    assertEquals("the page outgrew its window by $left dp", 0f, left)
}

/** The pages print times in the device's zone; UTC keeps the goldens the same on every machine. */
class Utc : ExternalResource() {
    private val zone = TimeZone.getDefault()

    override fun before() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    override fun after() = TimeZone.setDefault(zone)
}
