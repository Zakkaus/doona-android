package dev.doona.android.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.icons.R as IconsR

/** Feedback: progress, the progress bar, the empty state. */
@Composable
internal fun FeedbackGallery() = GalleryPage("Feedback") {
    val space = DoonaTheme.spacing
    Section("DoonaProgress: small, inline") {
        Row(horizontalArrangement = Arrangement.spacedBy(space.section), verticalAlignment = Alignment.CenterVertically) {
            DoonaProgress()
            DoonaProgress(size = DoonaProgressSize.Inline)
        }
    }
    Section("DoonaProgressBar: low, nearly used, used, over") {
        InsetCard {
            DoonaProgressBar(0.42f)
            DoonaProgressBar(0.93f)
            DoonaProgressBar(1f)
            DoonaProgressBar(7f)
        }
    }
    Section("DoonaEmptyState: with an action, without, long text") {
        Column(verticalArrangement = Arrangement.spacedBy(space.section)) {
            DoonaEmptyState(IconsR.drawable.sym_link, "沒有訂閱", "新增訂閱後，節點會出現在這裡。", actionLabel = stringResource(R.string.group_new_group), onAction = {})
            DoonaEmptyState(IconsR.drawable.sym_search, "No matching app", "Try a different name.")
            DoonaEmptyState(
                IconsR.drawable.sym_database,
                "A title that is long enough to wrap onto a second line",
                "This line is long too, so that it wraps and the block stays centred and does not touch the edges of its parent at any font size.",
                actionLabel = "新增訂閱並立即測試群組裡的每一個節點",
                onAction = {},
            )
        }
    }
}
