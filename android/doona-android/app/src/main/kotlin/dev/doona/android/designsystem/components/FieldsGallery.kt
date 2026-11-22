package dev.doona.android.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** Fields: the text field with a label in each of its states, and the search field. */
@Composable
internal fun FieldsGallery() = GalleryPage("Fields") {
    val space = DoonaTheme.spacing
    Section("DoonaTextField: empty, filled, error, disabled") {
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaTextField(rememberTextFieldState(), label = "Subscription URL", placeholder = "https://")
            DoonaTextField(rememberTextFieldState("https://example.com/sub"), label = "Subscription URL")
            DoonaTextField(rememberTextFieldState("example.com"), label = "Subscription URL", error = stringResource(R.string.error_fetch_failed))
            DoonaTextField(rememberTextFieldState("https://example.com/sub"), label = "Subscription URL", enabled = false)
        }
    }
    Section("DoonaTextField: supporting text, trailing action") {
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaTextField(rememberTextFieldState(), label = "Subscription URL", placeholder = "https://", supportingText = "The link your provider gave you")
            DoonaTextField(
                rememberTextFieldState("https://example.com/sub"),
                label = "Subscription URL",
                trailing = { DoonaIconAction(R.drawable.ic_more_vertical, stringResource(R.string.ui_more_options), onClick = {}) },
            )
        }
    }
    Section("DoonaTextField: long text") {
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaTextField(
                rememberTextFieldState("https://example.com/api/v1/client/subscribe?token=0123456789abcdef0123456789abcdef"),
                label = "訂閱網址",
                supportingText = "訂閱網址與用來辨識這份訂閱的名稱，過長時會換行顯示，不截斷",
            )
            DoonaTextField(
                rememberTextFieldState("https://example.com/api/v1/client/subscribe?token=0123456789abcdef0123456789abcdef"),
                label = "訂閱網址",
                error = "無法下載訂閱：伺服器回應逾時，請確認網址與目前的網路連線後再試一次",
                trailing = { DoonaIconAction(R.drawable.ic_more_vertical, stringResource(R.string.ui_more_options), onClick = {}) },
            )
        }
    }
    Section("DoonaSearchField: empty, filled, long") {
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaSearchField(rememberTextFieldState(), placeholder = stringResource(R.string.ui_search))
            DoonaSearchField(rememberTextFieldState("Chrome"), placeholder = stringResource(R.string.ui_search))
            DoonaSearchField(rememberTextFieldState("com.example.verylongapplicationidentifier.that.runs.past.the.edge"), placeholder = stringResource(R.string.ui_search))
        }
    }
}
