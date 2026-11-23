package dev.doona.android.feature.activity

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine

/** A connected engine with a minute of traffic, as previews and screenshot tests show the screen. */
fun sampleActivityState(error: ErrorCode? = null): ActivityUiState {
    val engine = FakeEngine(FakeEngine.SAMPLE_GROUPS, status = Status.Running(sinceEpochMs = 0), traffic = FakeEngine.SAMPLE_TRAFFIC)
    return activityUiState(engine.status.value, engine.traffic.value, engine.settings.value, engine.groups.value, error)
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ActivityScreenPreview() {
    DoonaTheme { ActivityScreen(sampleActivityState(), onAction = {}, onConnect = {}, onOpenGroup = {}) }
}

@Preview(name = "Error", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun ActivityScreenErrorPreview() {
    DoonaTheme { ActivityScreen(sampleActivityState(error = ErrorCode.Busy), onAction = {}, onConnect = {}, onOpenGroup = {}) }
}
