package dev.doona.android.feature.localnetwork

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import dev.doona.android.designsystem.components.DoonaConfirmKind
import dev.doona.android.designsystem.components.DoonaConfirmPanel
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.Dark
import dev.doona.android.testing.Light
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.Variant
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.showsContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The local network prompts in light and dark: the refusal, which leads to the app's settings, and the reason shown
 * before the system's question. Each is the dialog's card on its underlay, as the dialog's window shows it, with the
 * words [LocalNetworkPrompts] uses; goldens in `src/test/screenshots/local-network-<prompt>-<variant>.png`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class LocalNetworkPromptShotTest(private val prompt: LocalNetworkPrompt, private val variant: Variant) {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "src/test/screenshots"

    @Test
    fun matchesGoldenAndPassesAccessibilityChecks() {
        RuntimeEnvironment.setQualifiers(variant.qualifiers)
        compose.setContent { DoonaTheme { PhoneFrame { OnUnderlay(prompt) } } }
        compose.drawFully()
        val name = "local-network-${prompt.name.lowercase()}-${variant.name}"
        val image = compose.onRoot().captureToImage()
        assertTrue("$name: the content area shows nothing but fills", image.showsContent(compose.density.density))
        image.asAndroidBitmap().captureRoboImage("$dir/$name.png")
        compose.onRoot().checkRoboAccessibility()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> =
            listOf(LocalNetworkPrompt.Denied, LocalNetworkPrompt.Ask).flatMap { p -> listOf(Light, Dark).map { arrayOf(p, it) } }
    }
}

/** The page's colour under the scrim, and the card in the middle with the gutter the dialog keeps from the edges. */
@Composable
private fun OnUnderlay(prompt: LocalNetworkPrompt) {
    val text = prompt.text()
    Box(Modifier.fillMaxSize().background(DoonaTheme.colors.window)) {
        Box(
            Modifier.fillMaxSize().background(DoonaTheme.colors.scrim).padding(DoonaTheme.spacing.gutter),
            contentAlignment = Alignment.Center,
        ) {
            DoonaConfirmPanel(text.title, text.body, text.confirm, text.dismiss, onConfirm = {}, onDismiss = {}, kind = DoonaConfirmKind.Default)
        }
    }
}
