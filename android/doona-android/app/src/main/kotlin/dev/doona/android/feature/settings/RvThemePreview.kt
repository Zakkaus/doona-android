package dev.doona.android.feature.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvChrome
import dev.doona.android.designsystem.rv.RvSchemes
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsSize
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvThemeMode

/** The swatch the page shows as chosen: the first (the scheme's own primary) while the wallpaper's colour is followed. */
internal val RvAppearance.shownAccent: Int get() = if (followWallpaper) 0 else accent

/** Whether these choices are dark when the system is [systemDark]: the system's for 跟隨系統, else the chosen mode. */
internal fun RvAppearance.isDark(systemDark: Boolean): Boolean = when (mode) {
    RvThemeMode.System -> systemDark
    RvThemeMode.Light -> false
    RvThemeMode.Dark -> true
}

/**
 * What the choices would look like, Revolut's Appearance preview: the top of the real 活動 page (its top bar, hero, dot
 * map and filled round actions, S8-12), scaled down in a phone-shaped panel in a card and wearing the chosen theme. It is
 * a picture of [page]: the screen reader hears 預覽 and a touch does nothing.
 */
@Composable
internal fun RvThemePreview(appearance: RvAppearance, schemes: RvSchemes, page: RvThemePreviewPage, modifier: Modifier = Modifier) {
    val isDark = appearance.isDark(Rv.colors.dark)
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
                    RvTheme(schemes, isDark, appearance.shownAccent, page)
                }
            }
            // Above the page, so no tap or drag reaches it.
            Box(Modifier.matchParentSize().pointerInput(Unit) {})
        }
    }
}

/** The preview shows the 活動 page at this share of its size. */
private const val PREVIEW_SCALE = 0.6f

/** The page the preview draws, scaled down, inside the RV theme the choices give: 活動. */
internal typealias RvThemePreviewPage = @Composable () -> Unit
