package dev.doona.android.feature.onboarding

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.subscriptions.AddFormFields
import dev.doona.android.feature.subscriptions.AddFormState

/** The page as an engine with no subscription shows it; [startFailed] is a start that failed for want of a node. */
fun sampleFirstRunState(startFailed: Boolean = false): FirstRunUiState {
    val status = if (startFailed) Status.Failed(FailureReason.StartFailed) else Status.Stopped
    return firstRunUiState(ready = true, subscriptionCount = 0, status = status, form = AddFormState())
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FirstRunScreenPreview() {
    DoonaTheme { FirstRunScreen(sampleFirstRunState(), AddFormFields(), onAction = {}) }
}

@Preview(name = "Start failed", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun FirstRunScreenStartFailedPreview() {
    DoonaTheme { FirstRunScreen(sampleFirstRunState(startFailed = true), AddFormFields(), onAction = {}) }
}
