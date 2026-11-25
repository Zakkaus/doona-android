package dev.doona.android.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.designsystem.theme.DoonaTheme

/**
 * The app's theme: RV's tokens on the system scheme (the wallpaper's on Android 12 and later), light or dark as the
 * system is. The old pages not yet ported read [DoonaTheme], which gets the same scheme, so both draw in one palette
 * until the page lanes replace them (plan §2.5); RV parts read only `Rv`.
 */
@Composable
internal fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = rvSystemScheme(dark)
    RvTheme(scheme, dark) { DoonaTheme(dark, scheme, content) }
}
