package dev.doona.android.prototype.rv.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.doona.android.R
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvHero
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvPlace
import dev.doona.android.prototype.rv.rvDotMap

/** One accent: its name for a screen reader and its primary in the light theme. */
internal class RvAccent(@StringRes val name: Int, val light: Color)

/** The five colours the theme page offers when the wallpaper's is not followed. */
internal val RV_ACCENTS = listOf(
    RvAccent(R.string.rv_settings_accent_blue, RvAccentColors.blue),
    RvAccent(R.string.rv_settings_accent_teal, RvAccentColors.teal),
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

/**
 * What the theme choices would look like: a mini 活動 hero, in the page's own card, wearing the chosen theme. 跟隨系統 shows
 * the theme the page is in; with the wallpaper's colour not followed, the hero takes the chosen accent.
 */
@Composable
internal fun RvThemePreview(appearance: RvAppearance, light: ColorScheme, dark: ColorScheme, modifier: Modifier = Modifier) {
    val isDark = when (appearance.mode) {
        RvThemeMode.System -> Rv.colors.dark
        RvThemeMode.Light -> false
        RvThemeMode.Dark -> true
    }
    val base = if (isDark) dark else light
    val scheme = if (appearance.followWallpaper) base else base.withAccent(RV_ACCENTS[appearance.accent], isDark)
    val label = stringResource(R.string.rv_settings_preview)
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RvSettingsSize.cardRadius))
            .background(Rv.colors.card)
            .padding(RvSettingsSpace.previewPad)
            .clearAndSetSemantics { contentDescription = label },
    ) {
        RvTheme(scheme, isDark) { MiniHero() }
    }
}

/** The 活動 header in small: the dot map with the node's place marked, the state, the rate, and the four round actions. */
@Composable
private fun MiniHero() {
    val colors = Rv.colors
    Column(
        Modifier
            .fillMaxWidth()
            .height(RvSettingsSize.previewHeight)
            .clip(RoundedCornerShape(RvSettingsSize.previewRadius))
            .background(colors.page)
            .rvDotMap(RvPlace.TW, colors.dots, RvSettingsSize.previewMarkerTop),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(RvSettingsSize.previewCaptionTop))
        RvSettingsText("${stringResource(R.string.backend_connected)} · ${stringResource(R.string.mode_global)}", RvSettingsType.previewCaption)
        Spacer(Modifier.height(RvSettingsSpace.previewCaptionGap))
        RvHero(stringResource(R.string.rv_settings_preview_rate), stringResource(R.string.rv_settings_preview_unit))
        Spacer(Modifier.height(RvSettingsSpace.previewGap))
        Row(horizontalArrangement = Arrangement.spacedBy(RvSettingsSpace.previewActionGap)) {
            MINI_ACTIONS.forEach { MiniAction(it) }
        }
    }
}

@Composable
private fun MiniAction(@DrawableRes icon: Int) {
    Box(
        Modifier.size(RvSettingsSize.previewDisc).clip(CircleShape).background(Rv.colors.discOnHeader),
        contentAlignment = Alignment.Center,
    ) { RvGlyph(icon, RvSettingsSize.previewGlyph, Rv.colors.text) }
}

private val MINI_ACTIONS = listOf(R.drawable.rv_sym_power, R.drawable.rv_sym_directions, R.drawable.rv_sym_speed, R.drawable.rv_sym_more_horiz)
