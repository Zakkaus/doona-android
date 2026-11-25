package dev.doona.android.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvChrome
import dev.doona.android.designsystem.rv.RvColors
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvRadio
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsAlpha
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvSettingsSection
import dev.doona.android.designsystem.rv.RvSettingsSize
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.RvSettingsSwitchRow
import dev.doona.android.designsystem.rv.RvSettingsText
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvPressSelect
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The page 主題 opens, after Revolut's Appearance page (Addendum A): a large title that collapses into the bar, then three
 * titled cards: 預覽, the preview of the choice; 模式, the three modes side by side, each a small picture of the page over
 * its label and radio; and 強調色, the wallpaper switch and five colour discs. The first disc is the scheme's own primary;
 * the page itself wears the chosen accent, so its radio, switch and preview agree with the ringed disc (S8-10, S07).
 * [light] and [dark] are the two schemes the page and the preview wear; the discs are dimmed and inert while the
 * wallpaper's colour is followed, since the accent then comes from it. [previewPage] is the page 預覽 draws.
 */
@Composable
internal fun RvThemeScreen(
    appearance: RvAppearance,
    onChange: (RvAppearance) -> Unit,
    light: ColorScheme,
    dark: ColorScheme,
    previewPage: RvThemePreviewPage,
    onBack: () -> Unit,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_theme)
    val pageDark = Rv.colors.dark
    RvTheme(appearance.scheme(if (pageDark) dark else light, pageDark), pageDark) {
        RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
            RvLargeTitle(title, titleBlock, insets)
            // Each card has its outside title, as 強調色 has (N-07); the first one sits on the header's gap.
            RvSettingsColumn {
                RvSettingsSection(stringResource(R.string.rv_settings_preview), first = true)
                RvThemePreview(appearance, light, dark, previewPage)
                RvSettingsSection(stringResource(R.string.rv_settings_mode))
                ModeCard(appearance, light, dark, onChange)
                RvSettingsSection(stringResource(R.string.rv_settings_accent))
                AccentCard(appearance, if (pageDark) dark.primary else light.primary, onChange)
            }
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
                        .rvPressSelect(RoundedCornerShape(Rv.highlightRadius), selected, { onChange(appearance.copy(mode = mode)) })
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
    val shape = RoundedCornerShape(Rv.highlightRadius)
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
            .rvPressSelect(CircleShape, selected, onClick, enabled)
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

/**
 * 主題 in the app: the [store]'s choices, each stored as it is made. The app's theme reads the same store, so the whole
 * app, this page included, wears a choice as soon as it lands. [light] and [dark] are the system's schemes.
 */
@Composable
internal fun ThemeRoute(store: SettingsStore, light: ColorScheme, dark: ColorScheme, previewPage: RvThemePreviewPage, onBack: () -> Unit) {
    val viewModel: ThemeViewModel = viewModel(factory = ThemeViewModel.factory(store))
    // The page waits for the store, as 設定 does, rather than show a choice the user may not have made.
    val appearance = viewModel.appearance.collectAsStateWithLifecycle().value ?: return RvSettingsLoading()
    RvThemeScreen(appearance, viewModel::set, light, dark, previewPage, onBack)
}

/** 主題's choices as the store has them, null until it answers; a choice is stored in the view model's scope, so leaving the page does not cancel it. */
internal class ThemeViewModel(private val store: SettingsStore) : ViewModel() {
    val appearance: StateFlow<RvAppearance?> = store.appearance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun set(appearance: RvAppearance) {
        viewModelScope.launch { store.setAppearance(appearance) }
    }

    companion object {
        fun factory(store: SettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { ThemeViewModel(store) }
        }
    }
}
