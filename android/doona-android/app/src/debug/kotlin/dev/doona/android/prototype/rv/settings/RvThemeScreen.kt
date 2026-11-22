package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * The page 主題 opens, after Revolut's Appearance page: a large title that collapses into the bar, a card with a radio
 * list of the themes, a 強調色 group with the wallpaper switch and five colour discs, and a preview of the choice.
 * [light] and [dark] are the two schemes the preview wears; the discs are dimmed and inert while the wallpaper's colour
 * is followed, since the accent then comes from it.
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
    RvScrollPage(stringResource(R.string.rv_settings_theme), insets, Modifier.fillMaxSize(), onBack) { titleBlock ->
        RvBarSpacer(insets)
        Spacer(Modifier.height(RvSettingsSpace.titleTop))
        RvSettingsText(
            stringResource(R.string.rv_settings_theme),
            RvSettingsType.title,
            titleBlock.padding(horizontal = RvSettingsSpace.margin).semantics { heading() },
        )
        Spacer(Modifier.height(RvSettingsSpace.titleBottom))
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            ModeCard(appearance, onChange)
            RvSettingsSection(stringResource(R.string.rv_settings_accent))
            AccentCard(appearance, onChange)
            Spacer(Modifier.height(RvSettingsSpace.cardGap))
            RvThemePreview(appearance, light, dark)
        }
        Spacer(Modifier.height(RvSettingsSpace.bottom))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

@Composable
private fun ModeCard(appearance: RvAppearance, onChange: (RvAppearance) -> Unit) {
    RvSettingsCard {
        RvThemeMode.entries.forEach { mode ->
            RvSettingsRadioRow(stringResource(mode.label), appearance.mode == mode) { onChange(appearance.copy(mode = mode)) }
        }
    }
}

@Composable
private fun AccentCard(appearance: RvAppearance, onChange: (RvAppearance) -> Unit) {
    RvSettingsCard {
        RvSettingsSwitchRow(
            stringResource(R.string.rv_settings_follow_wallpaper),
            R.drawable.rv_settings_ic_wallpaper,
            appearance.followWallpaper,
            { onChange(appearance.copy(followWallpaper = it)) },
        )
        val enabled = !appearance.followWallpaper
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = RvSettingsSpace.rowInset, vertical = RvSettingsSpace.accentPad)
                .graphicsLayer { alpha = if (enabled) 1f else RvSettingsAlpha.DISABLED },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RV_ACCENTS.forEachIndexed { i, accent ->
                AccentDisc(accent, i == appearance.accent, enabled) { onChange(appearance.copy(accent = i)) }
            }
        }
    }
}

/** A colour disc; the selected one has a ring of the text colour round it, with a gap of the card's between. */
@Composable
private fun AccentDisc(accent: RvAccent, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val name = stringResource(accent.name)
    val ring = if (selected) Modifier.border(RvSettingsSize.accentRingStroke, Rv.colors.text, CircleShape) else Modifier
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(RvSettingsSize.accentRing)
            .clip(CircleShape)
            .selectable(selected, interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .then(ring)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(RvSettingsSize.accentDisc).clip(CircleShape).background(accent.primary(Rv.colors.dark)))
    }
}

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
