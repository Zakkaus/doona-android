package dev.tunbench.preview.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme

/**
 * The Google Home token set (HomeTheme.kt) with Google Sans. Every colour is a scheme role: the
 * wallpaper's scheme on API 31+, as Google Home on Pixel, otherwise the one-seed fallback.
 * [fixedColorScheme] replaces both, for screenshots that stand in for a wallpaper.
 */
@Composable
fun TunBenchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    fixedColorScheme: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        fixedColorScheme != null -> fixedColorScheme

        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> HomeDarkScheme

        else -> HomeLightScheme
    }
    MaterialExpressiveTheme(colorScheme = colorScheme, typography = Typography().inGoogleSans()) {
        ProvideVicoTheme(rememberM3VicoTheme(), content)
    }
}
