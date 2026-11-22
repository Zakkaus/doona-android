package dev.doona.android.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets
import org.robolectric.RuntimeEnvironment

/** Sets [content] in the theme and with the insets of a phone's bars, as the tests of a screen do. */
fun ComposeContentTestRule.setDoonaContent(content: @Composable () -> Unit) =
    setContent { CompositionLocalProvider(LocalFrameInsets provides FrameInsets) { DoonaTheme { content() } } }

/** Sets [content] as [setDoonaContent] does, in the window and at the font scale of [variant]. */
fun ComposeContentTestRule.setVariantContent(variant: Variant, content: @Composable () -> Unit) {
    RuntimeEnvironment.setQualifiers(variant.qualifiers)
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, variant.fontScale), LocalFrameInsets provides FrameInsets) { DoonaTheme { content() } }
    }
}
