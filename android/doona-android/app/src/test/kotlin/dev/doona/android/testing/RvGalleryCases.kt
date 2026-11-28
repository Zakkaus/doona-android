package dev.doona.android.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.LocalGlassBlur
import dev.doona.android.designsystem.components.RvChartsGallery
import dev.doona.android.designsystem.components.RvChromeGallery
import dev.doona.android.designsystem.components.RvControlsGallery
import dev.doona.android.designsystem.components.RvMapsGallery
import dev.doona.android.designsystem.components.RvPageGallery
import dev.doona.android.designsystem.components.RvRowsGallery
import dev.doona.android.designsystem.theme.DoonaTheme

/**
 * What a gallery page of the design system holds, stated in spacing roles so the rules do not read back what was drawn:
 * the layout roles it [shows] (the probe must find each), whether it is a [scaffold] with the floating nav, and the roles
 * the components promise: rows padded by [rowPadding] with [rowGap] after a leading visual, section titles [sectionAbove]
 * their text and [sectionBelow] it, the back disc on the [gutter], and [navGap] between the nav and the end of the content.
 */
internal class GalleryLayout(
    val shows: Set<LayoutRole>,
    val scaffold: Boolean = false,
    val rowPadding: Dp = DoonaTheme.spacing.rowPadding,
    val rowGap: Dp = DoonaTheme.spacing.rowGap,
    val sectionAbove: Dp = DoonaTheme.spacing.sectionAbove,
    val sectionBelow: Dp = DoonaTheme.spacing.sectionBelow,
    val gutter: Dp = DoonaTheme.spacing.gutter,
    val navGap: Dp = DoonaTheme.chrome.navGap,
)

/** A gallery page of the design system as the tests render it: glass through its solid fallback, as Robolectric draws no blur. */
internal class GalleryCase(val name: String, val layout: GalleryLayout, private val page: @Composable () -> Unit) {
    val content: @Composable () -> Unit = { CompositionLocalProvider(LocalGlassBlur provides false) { page() } }
}

/** The design system's gallery pages, one per family of components, with the layout each one states. */
internal fun rvGalleryCases(): List<GalleryCase> {
    val rows = setOf(LayoutRole.ListCard, LayoutRole.ListRow)
    return listOf(
        GalleryCase(
            "gallery-rv-rows",
            GalleryLayout(rows + setOf(LayoutRole.Card, LayoutRole.Heading, LayoutRole.Toggle, LayoutRole.Flag, LayoutRole.Mark, LayoutRole.Tag)),
        ) { RvRowsGallery() },
        GalleryCase(
            "gallery-rv-controls",
            GalleryLayout(setOf(LayoutRole.Segmented, LayoutRole.Toggle, LayoutRole.Chip, LayoutRole.Button, LayoutRole.Tag)),
        ) { RvControlsGallery() },
        GalleryCase("gallery-rv-chrome", GalleryLayout(rows + setOf(LayoutRole.Chrome, LayoutRole.Heading, LayoutRole.Button))) { RvChromeGallery() },
        GalleryCase("gallery-rv-charts", GalleryLayout(setOf(LayoutRole.Chart, LayoutRole.Figure))) { RvChartsGallery() },
        GalleryCase("gallery-rv-maps", GalleryLayout(setOf(LayoutRole.Chart, LayoutRole.ListCard))) { RvMapsGallery() },
        GalleryCase(
            "gallery-rv-page",
            GalleryLayout(rows + setOf(LayoutRole.Chrome, LayoutRole.Heading, LayoutRole.Toggle, LayoutRole.Mark), scaffold = true),
        ) { RvPageGallery() },
    )
}
