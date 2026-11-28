package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_ACCENTS
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvColorChoice
import dev.doona.android.designsystem.rv.RvColors
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvPictureChoice
import dev.doona.android.designsystem.rv.RvSchemes
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsAlpha
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvSettingsSection
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwatch
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.primary
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The page 主題 opens, after Revolut's Appearance page (Addendum A): a large title that collapses into the bar, then three
 * titled cards: 預覽, the preview of the choice; 模式, the three modes side by side, each a small picture of the page over
 * its label and radio; and 強調色, the wallpaper switch and five colour discs. The first disc is the scheme's own primary;
 * the page itself wears the chosen accent, so its radio, switch and preview agree with the ringed disc (S8-10, S07).
 * [schemes] are the light and dark schemes the page and the preview wear; the discs are dimmed and inert while the
 * wallpaper's colour is followed, since the accent then comes from it. [previewPage] is the page 預覽 draws.
 */
@Composable
internal fun RvThemeScreen(
    appearance: RvAppearance,
    onChange: (RvAppearance) -> Unit,
    schemes: RvSchemes,
    previewPage: RvThemePreviewPage,
    onBack: () -> Unit,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_theme)
    val pageDark = Rv.colors.dark
    RvTheme(schemes, pageDark, appearance.shownAccent) {
        RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
            RvLargeTitle(title, titleBlock, insets)
            // Each card has its outside title, as 強調色 has (N-07); the first one sits on the header's gap.
            RvSettingsColumn {
                RvSettingsSection(stringResource(R.string.rv_settings_preview), first = true)
                RvThemePreview(appearance, schemes, previewPage)
                RvSettingsSection(stringResource(R.string.rv_settings_mode))
                ModeCard(appearance, schemes, onChange)
                RvSettingsSection(stringResource(R.string.rv_settings_accent))
                AccentCard(appearance, schemes.primary(pageDark), onChange)
            }
        }
    }
}

/** The three modes side by side: each a picture of the page in that mode, its name and its radio; the whole column picks it. */
@Composable
private fun ModeCard(appearance: RvAppearance, schemes: RvSchemes, onChange: (RvAppearance) -> Unit) {
    val lightColors = remember(schemes) { schemes.colors(dark = false) }
    val darkColors = remember(schemes) { schemes.colors(dark = true) }
    RvSettingsCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.tight)) {
            RvThemeMode.entries.forEach { mode ->
                val selected = appearance.mode == mode
                RvPictureChoice(stringResource(mode.label), selected, { onChange(appearance.copy(mode = mode)) }, Modifier.weight(1f)) {
                    ModeThumb(mode, lightColors, darkColors)
                }
            }
        }
    }
}

/** A small picture of the page in [mode]: the page's colour, a dot for the hero, two cards; 跟隨系統 is half of each. */
@Composable
private fun ModeThumb(mode: RvThemeMode, light: RvColors, dark: RvColors) {
    when (mode) {
        RvThemeMode.Light -> RvSwatch(light)
        RvThemeMode.Dark -> RvSwatch(dark)
        RvThemeMode.System -> RvSwatch(light, split = dark)
    }
}

@Composable
private fun AccentCard(appearance: RvAppearance, own: Color, onChange: (RvAppearance) -> Unit) {
    RvSettingsCard {
        RvSwitchRow(R.drawable.rv_sym_image, stringResource(R.string.rv_settings_follow_wallpaper), appearance.followWallpaper, onChange = { onChange(appearance.copy(followWallpaper = it)) })
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
                RvColorChoice(colour, stringResource(name), i == appearance.shownAccent, enabled, { onChange(appearance.copy(accent = i)) })
            }
        }
    }
}

/**
 * 主題 in the app: the [store]'s choices, each stored as it is made. The app's theme reads the same store, so the whole
 * app, this page included, wears a choice as soon as it lands. [schemes] are the system's.
 */
@Composable
internal fun ThemeRoute(store: SettingsStore, schemes: RvSchemes, previewPage: RvThemePreviewPage, onBack: () -> Unit) {
    val viewModel: ThemeViewModel = viewModel(factory = ThemeViewModel.factory(store))
    // The page waits for the store, as 設定 does, rather than show a choice the user may not have made.
    val appearance = viewModel.appearance.collectAsStateWithLifecycle().value ?: return RvSettingsLoading()
    RvThemeScreen(appearance, viewModel::set, schemes, previewPage, onBack)
}

/** 主題's choices as the store has them, null until it answers; the store writes a choice in its own scope, so leaving the page at once still keeps it. */
internal class ThemeViewModel(private val store: SettingsStore) : ViewModel() {
    val appearance: StateFlow<RvAppearance?> = store.appearance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun set(appearance: RvAppearance) {
        store.setAppearance(appearance)
    }

    companion object {
        fun factory(store: SettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { ThemeViewModel(store) }
        }
    }
}
