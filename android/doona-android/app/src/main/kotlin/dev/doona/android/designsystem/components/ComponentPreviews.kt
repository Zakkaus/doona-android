package dev.doona.android.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Tone
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.icons.R as IconsR

/*
 * The design system on several pages (the three here, and `InputsGallery`, `FieldsGallery`, `FeedbackGallery` and `DialogGallery`), every component in every
 * state it has. The preview pane and the screenshot goldens show the same pages, so a component that is added or changed
 * shows up in both. The names and numbers on these pages are made up.
 */

@Composable
internal fun GalleryPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    val space = DoonaTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .background(DoonaTheme.colors.page)
            .windowInsetsPadding(DoonaTheme.insets)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.sheetTop),
        verticalArrangement = Arrangement.spacedBy(space.section),
    ) {
        DoonaPageTitle(title)
        content()
    }
}

@Composable
internal fun Section(name: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item)) {
        DoonaText(name, DoonaTheme.type.footnote, color = DoonaTheme.colors.textSecondary)
        content()
    }
}
