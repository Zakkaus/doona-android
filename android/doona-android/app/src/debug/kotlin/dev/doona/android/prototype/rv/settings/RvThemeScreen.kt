package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvChrome
import dev.doona.android.prototype.rv.RvColors
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvRadio
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * The page 主題 opens, after Revolut's Appearance page (Addendum A): a large title that collapses into the bar, the
 * preview of the choice first, then the three modes side by side, each a small picture of the page over its label and
 * radio, then a 強調色 group with the wallpaper switch and five colour discs. The first disc is the scheme's own primary;
 * the page itself wears the chosen accent, so its radio, switch and preview agree with the ringed disc (S8-10, S07).
 * [light] and [dark] are the two schemes the page and the preview wear; the discs are dimmed and inert while the
 * wallpaper's colour is followed, since the accent then comes from it.
 */
@Composable
internal fun RvThemeScreen(
    appearance: RvAppearance,
    onChange: (RvAppearance) -> Unit,
    light: ColorScheme,
    dark: ColorScheme,
    onBack: () -> Unit,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_theme)
    val pageDark = Rv.colors.dark
    RvTheme(appearance.scheme(if (pageDark) dark else light, pageDark), pageDark) {
        RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
            RvLargeTitle(title, titleBlock, insets)
            Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin), verticalArrangement = Arrangement.spacedBy(RvSpace.cardGap)) {
                RvThemePreview(appearance, light, dark)
                ModeCard(appearance, light, dark, onChange)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
                RvSettingsSection(stringResource(R.string.rv_settings_accent))
                AccentCard(appearance, if (pageDark) dark.primary else light.primary, onChange)
            }
            Spacer(Modifier.height(RvSettingsSpace.bottom))
            Spacer(Modifier.windowInsetsBottomHeight(insets))
        }
    }
}

/** The three modes side by side: each a picture of the page in that mode, its name and its radio; the whole column picks it. */
@Composable
private fun ModeCard(appearance: RvAppearance, light: ColorScheme, dark: ColorScheme, onChange: (RvAppearance) -> Unit) {
    val lightColors = remember(light) { RvColors(light, dark = false) }
    val darkColors = remember(dark) { RvColors(dark, dark = true) }
    RvSettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.tight)) {
            RvThemeMode.entries.forEach { mode ->
                val selected = appearance.mode == mode
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(RvSize.groupRadius))
                        .selectable(selected, interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), role = Role.RadioButton) {
                            onChange(appearance.copy(mode = mode))
                        }
                        .padding(vertical = RvSettingsSpace.modePad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ModeThumb(mode, lightColors, darkColors)
                    Spacer(Modifier.height(RvSettingsSpace.modeGap))
                    RvSettingsText(stringResource(mode.label), Rv.type.action, align = TextAlign.Center)
                    Spacer(Modifier.height(RvSettingsSpace.modeGap))
                    RvRadio(selected)
                }
            }
        }
    }
}

/** A small picture of the page in [mode]: the page's colour, a dot for the hero, two cards; 跟隨系統 is half of each. */
@Composable
private fun ModeThumb(mode: RvThemeMode, light: RvColors, dark: RvColors) {
    val shape = RoundedCornerShape(RvSettingsSize.modeRadius)
    Canvas(Modifier.size(RvSettingsSize.modeThumb).clip(shape).border(RvChrome.ring, Rv.colors.flagRing, shape)) {
        when (mode) {
            RvThemeMode.Light -> page(light)

            RvThemeMode.Dark -> page(dark)

            RvThemeMode.System -> {
                page(light)
                clipRect(left = size.width / 2) { page(dark) }
            }
        }
    }
}

private fun DrawScope.page(colors: RvColors) {
    drawRect(colors.page)
    drawCircle(colors.positive, size.width * THUMB_DOT, Offset(size.width / 2, size.height * THUMB_DOT_Y))
    val inset = size.width * THUMB_INSET
    val radius = CornerRadius(inset)
    for (top in THUMB_CARDS) {
        drawRoundRect(colors.card, Offset(inset, size.height * top), Size(size.width - 2 * inset, size.height * THUMB_CARD), radius)
    }
}

@Composable
private fun AccentCard(appearance: RvAppearance, own: Color, onChange: (RvAppearance) -> Unit) {
    RvSettingsCard {
        RvSettingsSwitchRow(
            stringResource(R.string.rv_settings_follow_wallpaper),
            R.drawable.rv_sym_image,
            appearance.followWallpaper,
            { onChange(appearance.copy(followWallpaper = it)) },
        )
        val enabled = !appearance.followWallpaper
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RvSettingsSpace.accentInset, vertical = RvSettingsSpace.accentPad)
                .graphicsLayer { alpha = if (enabled) 1f else RvSettingsAlpha.DISABLED },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val colours = listOf(R.string.rv_settings_accent_current to own) + RV_ACCENTS.map { it.name to it.primary(Rv.colors.dark) }
            colours.forEachIndexed { i, (name, colour) ->
                AccentDisc(stringResource(name), colour, i == appearance.shownAccent, enabled) { onChange(appearance.copy(accent = i)) }
            }
        }
    }
}

/** A colour disc, 36 dp in a 48 dp target (S8-11); the selected one has a ring of the text colour round it. */
@Composable
private fun AccentDisc(name: String, colour: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(RvSize.touch)
            .clip(CircleShape)
            .selectable(selected, interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val ring = if (selected) Modifier.border(RvSettingsSize.accentRingStroke, Rv.colors.text, CircleShape) else Modifier
        Box(Modifier.size(RvSettingsSize.accentRing).then(ring), contentAlignment = Alignment.Center) {
            Box(Modifier.size(RvSettingsSize.accentDisc).clip(CircleShape).background(colour))
        }
    }
}

/** The thumbnail's hero dot (radius and centre as shares of its width and height) and its two cards' tops and height. */
private const val THUMB_DOT = 0.08f
private const val THUMB_DOT_Y = 0.2f
private const val THUMB_INSET = 0.12f
private val THUMB_CARDS = listOf(0.38f, 0.66f)
private const val THUMB_CARD = 0.22f

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvThemeScreenPreview() {
    val dark = isSystemInDarkTheme()
    var appearance by remember { mutableStateOf(rvSampleAppearance()) }
    RvTheme(rvSystemScheme(dark), dark) {
        RvThemeScreen(appearance, { appearance = it }, rvSystemScheme(false), rvSystemScheme(true), onBack = {})
    }
}
