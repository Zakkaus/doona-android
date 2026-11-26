package dev.doona.android.app

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.accented
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.feature.settings.isDark
import dev.doona.android.feature.settings.shownAccent

/**
 * The app's theme from 主題's stored choices: dark as the mode says (the system's for 跟隨系統), the wallpaper's scheme
 * with the chosen accent, as the theme page's preview wears it, and the system bars' icons to match. Nothing is drawn
 * until the store answers (a frame or two of the window's background), so a chosen mode never flashes the other one.
 */
@Composable
internal fun AppTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val stored by settings.appearance.collectAsStateWithLifecycle(null)
    val appearance = stored?.let(::rvAppearance) ?: return
    val dark = appearance.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val scheme = remember(context, appearance, dark) { rvSystemScheme(context, dark).accented(appearance.shownAccent, dark) }
    LaunchedEffect(context, dark) {
        val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        (context as? ComponentActivity)?.enableEdgeToEdge(style, style)
    }
    RvTheme(scheme, dark) { DoonaTheme(dark, scheme, content) }
}

/**
 * RV's tokens on the system scheme (the wallpaper's on Android 12 and later), light or dark as [dark] says, with no
 * stored choice. The parts not yet ported read [DoonaTheme], which gets the same scheme in both, so both draw in one
 * palette (plan §2.5); RV parts read only `Rv`.
 */
@Composable
internal fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = rvSystemScheme(dark)
    RvTheme(scheme, dark) { DoonaTheme(dark, scheme, content) }
}
