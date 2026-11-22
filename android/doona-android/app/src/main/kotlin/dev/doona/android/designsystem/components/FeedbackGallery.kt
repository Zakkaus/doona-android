package dev.doona.android.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** Feedback: progress, the traffic meter, the empty state. */
@Composable
internal fun FeedbackGallery() = GalleryPage("Feedback") {
    val space = DoonaTheme.spacing
    Section("DoonaProgress: small, inline") {
        Row(horizontalArrangement = Arrangement.spacedBy(space.section), verticalAlignment = Alignment.CenterVertically) {
            DoonaProgress()
            DoonaProgress(size = DoonaProgressSize.Inline)
        }
    }
    Section("DoonaMeter: low, nearly used, used, long text") {
        DoonaCard(Modifier.fillMaxWidth()) {
            DoonaMeter(0.42f, "42.0 GB / 100 GB")
            DoonaMeter(0.93f, "93.0 GB / 100 GB")
            DoonaMeter(1f, "100 GB / 100 GB")
            DoonaMeter(0.7f, "已使用 70.5 GB，共 100 GB；下次重設日期為 2027-01-01，到期日為 2027-03-15")
        }
    }
    Section("DoonaEmptyState: with an action, without, long text") {
        Column(verticalArrangement = Arrangement.spacedBy(space.section)) {
            DoonaEmptyState(R.drawable.ic_link, "沒有訂閱", "新增訂閱後，節點會出現在這裡。", actionLabel = stringResource(R.string.group_new_group), onAction = {})
            DoonaEmptyState(R.drawable.ic_search, "No matching app", "Try a different name.")
            DoonaEmptyState(
                R.drawable.ic_data,
                "A title that is long enough to wrap onto a second line",
                "This line is long too, so that it wraps and the block stays centred and does not touch the edges of its parent at any font size.",
                actionLabel = "新增訂閱並立即測試群組裡的每一個節點",
                onAction = {},
            )
        }
    }
}
