package dev.doona.android.prototype.rv.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvActivityScreen
import dev.doona.android.prototype.rv.RvChrome
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.rvSampleActivity

/** One accent: its name for a screen reader and its primary in the light theme. */
internal class RvAccent(@StringRes val name: Int, val light: Color)

/** The four fixed colours the theme page offers after the first swatch, which is the scheme's own primary (S8-10). */
internal val RV_ACCENTS = listOf(
    RvAccent(R.string.rv_settings_accent_blue, RvAccentColors.blue),
    RvAccent(R.string.rv_settings_accent_purple, RvAccentColors.purple),
    RvAccent(R.string.rv_settings_accent_orange, RvAccentColors.orange),
    RvAccent(R.string.rv_settings_accent_pink, RvAccentColors.pink),
)

/** The accent as a primary: as it is in light, lifted toward white in dark so it keeps its contrast on the black page. */
internal fun RvAccent.primary(dark: Boolean): Color = if (dark) lerp(light, Color.White, RvAccentColors.DARK_LIFT) else light

/** This scheme with [accent] for its primary roles, which are what the sample's dots, positive hue and download line read. */
internal fun ColorScheme.withAccent(accent: RvAccent, dark: Boolean): ColorScheme {
    val primary = accent.primary(dark)
    return copy(
        primary = primary,
        onPrimary = if (dark) RvAccentColors.onPrimaryDark else Color.White,
        primaryContainer = if (dark) lerp(primary, Color.Black, RvAccentColors.CONTAINER_DARK) else lerp(primary, Color.White, RvAccentColors.CONTAINER_LIGHT),
        onPrimaryContainer = onSurface,
    )
}

/** The swatch the page shows as chosen: the first (the scheme's own primary) while the wallpaper's colour is followed. */
internal val RvAppearance.shownAccent: Int get() = if (followWallpaper) 0 else accent

/**
 * The scheme [base] becomes under these choices: itself for the first swatch or the wallpaper's colour, else with the
 * chosen fixed accent. The theme page wears it too, so its radio, switch and preview agree with the ringed swatch (S07).
 */
internal fun RvAppearance.scheme(base: ColorScheme, dark: Boolean): ColorScheme =
    if (shownAccent == 0) base else base.withAccent(RV_ACCENTS[shownAccent - 1], dark)

/** Whether the preview is dark: the page's own theme for 跟隨系統, else the chosen one. */
internal fun RvAppearance.previewDark(pageDark: Boolean): Boolean = when (mode) {
    RvThemeMode.System -> pageDark
    RvThemeMode.Light -> false
    RvThemeMode.Dark -> true
}

/**
 * What the choices would look like, Revolut's Appearance preview: the top of the real 活動 page (its top bar, hero, dot
 * map and filled round actions, S8-12), scaled down in a phone-shaped panel in a card and wearing the chosen theme. It is
 * a picture: the screen reader hears 預覽 and a touch does nothing.
 */
@Composable
internal fun RvThemePreview(appearance: RvAppearance, light: ColorScheme, dark: ColorScheme, modifier: Modifier = Modifier) {
    val isDark = appearance.previewDark(Rv.colors.dark)
    val scheme = appearance.scheme(if (isDark) dark else light, isDark)
    val label = stringResource(R.string.rv_settings_preview)
    val size = RvSettingsSize
    val shape = RoundedCornerShape(Rv.highlightRadius)
    RvSettingsCard(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(RvSettingsSpace.previewPad)
                .wrapContentSize(Alignment.TopCenter)
                .size(size.previewScreen.width * PREVIEW_SCALE, size.previewHeight * PREVIEW_SCALE)
                .clip(shape)
                .border(RvChrome.ring, Rv.colors.flagRing, shape)
                .clearAndSetSemantics { contentDescription = label },
        ) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopCenter, unbounded = true)
                    .requiredSize(size.previewScreen)
                    .graphicsLayer {
                        scaleX = PREVIEW_SCALE
                        scaleY = PREVIEW_SCALE
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
            ) {
                CompositionLocalProvider(LocalFrameInsets provides WindowInsets(top = size.previewStatus)) {
                    RvTheme(scheme, isDark) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }
                }
            }
            // Above the page, so no tap or drag reaches it.
            Box(Modifier.matchParentSize().pointerInput(Unit) {})
        }
    }
}

/** The preview shows the 活動 page at this share of its size. */
private const val PREVIEW_SCALE = 0.6f
