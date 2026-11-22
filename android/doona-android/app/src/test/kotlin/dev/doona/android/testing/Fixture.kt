package dev.doona.android.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets

/** Sets [content] in the theme and with the insets of a phone's bars, as the tests of a screen do. */
fun ComposeContentTestRule.setDoonaContent(content: @Composable () -> Unit) =
    setContent { CompositionLocalProvider(LocalFrameInsets provides FrameInsets) { DoonaTheme { content() } } }
