package dev.doona.android.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import dev.doona.android.designsystem.theme.DoonaTheme

/** Feedback: progress, the traffic meter, the empty state. */
@Composable
internal fun FeedbackGallery() = GalleryPage("Feedback") {
    Section("DoonaProgress: small, inline") {
        Row(horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.section), verticalAlignment = Alignment.CenterVertically) {
            DoonaProgress()
            DoonaProgress(size = DoonaProgressSize.Inline)
        }
    }
}
