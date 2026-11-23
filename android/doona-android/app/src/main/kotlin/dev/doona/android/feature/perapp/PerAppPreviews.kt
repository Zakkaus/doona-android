package dev.doona.android.feature.perapp

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.fake.FakeInstalledApps

private val APPS = FakeInstalledApps.SAMPLE_APPS

/** The saved policy with nothing changed: every app uses the tunnel. */
fun sampleAllState() = perAppUiState(PerApp(), draft = null, installed = APPS, query = "", busy = false, error = null)

/** 只有勾選的 with three apps checked and the edit not yet saved. */
fun sampleCheckedState(): PerAppUiState {
    val packages = setOf("com.android.chrome", "org.telegram.messenger", "com.example.calc")
    return perAppUiState(PerApp(), PerApp(AppPolicy.OnlyListed, packages), APPS, query = "", busy = false, error = null)
}

/** 勾選的以外, narrowed by a search that matches the label and the package name of an app, which two of the sample apps do. */
fun sampleSearchState(query: String = "ma"): PerAppUiState =
    perAppUiState(PerApp(AppPolicy.ExceptListed, setOf("com.example.maps")), draft = null, installed = APPS, query = query, busy = false, error = null)

/** 只有勾選的 with nothing checked, which cannot be saved. */
fun sampleNeedsAppState() = perAppUiState(PerApp(), PerApp(AppPolicy.OnlyListed), APPS, query = "", busy = false, error = null)

/** A save that failed: the edits stay and the banner says why. */
fun sampleErrorState() = sampleCheckedState().let { perAppUiState(PerApp(), PerApp(AppPolicy.OnlyListed, it.checked), APPS, "", busy = false, error = ErrorCode.Busy) }

/** A save in progress. */
fun sampleBusyState() = sampleCheckedState().let { perAppUiState(PerApp(), PerApp(AppPolicy.OnlyListed, it.checked), APPS, "", busy = true, error = null) }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PerAppCheckedPreview() {
    DoonaTheme { PerAppScreen(sampleCheckedState(), onAction = {}, onBack = {}) }
}

@Preview(name = "All", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun PerAppAllPreview() {
    DoonaTheme { PerAppScreen(sampleAllState(), onAction = {}, onBack = {}) }
}

@Preview(name = "Error", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun PerAppErrorPreview() {
    DoonaTheme { PerAppScreen(sampleErrorState(), onAction = {}, onBack = {}) }
}
