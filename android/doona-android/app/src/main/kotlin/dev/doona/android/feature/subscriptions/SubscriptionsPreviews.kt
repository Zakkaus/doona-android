package dev.doona.android.feature.subscriptions

import android.content.res.Configuration
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription

/** A fixed "now", so the expiry of a sample does not pass with the calendar: 2026-11-22 12:00 UTC, midday so every time zone shows the same date. */
const val SAMPLE_NOW_MS = 1_795_348_800_000L

private const val NOV_20_MS = 1_795_176_000_000L
private const val DEC_31_MS = 1_798_718_400_000L
private const val GB = 1_000_000_000L

/** One with a quota and an expiry, one with neither. */
val SAMPLE_SUBSCRIPTION_LIST = listOf(
    Subscription(
        id = "s1",
        name = "harbor",
        url = "https://example.invalid/harbor",
        nodes = 12,
        updatedAtEpochMs = SAMPLE_NOW_MS,
        usedBytes = 38 * GB,
        totalBytes = 100 * GB,
        expiresAtEpochMs = DEC_31_MS,
    ),
    Subscription(id = "s2", name = "lighthouse", url = "https://example.invalid/lighthouse", nodes = 6, updatedAtEpochMs = NOV_20_MS),
)

/** One that is refreshing, one whose last refresh failed and whose expiry has passed, one that never updated. */
val SAMPLE_TROUBLED_LIST = listOf(
    Subscription(id = "s2", name = "lighthouse", url = "https://example.invalid/lighthouse", nodes = 6, updatedAtEpochMs = NOV_20_MS),
    Subscription(
        id = "s3",
        name = "bay",
        url = "https://example.invalid/bay",
        nodes = 4,
        updatedAtEpochMs = null,
        error = ErrorCode.FetchFailed.name,
        expiresAtEpochMs = NOV_20_MS,
    ),
)

fun sampleSubscriptionsState(
    subscriptions: List<Subscription> = SAMPLE_SUBSCRIPTION_LIST,
    updating: Set<String> = emptySet(),
    error: ErrorCode? = null,
    adding: Boolean = false,
    form: AddFormState = AddFormState(),
): SubscriptionsUiState =
    subscriptionsUiState(true, subscriptions, form, SubscriptionsLocal(updating = updating, adding = adding, error = error), SAMPLE_NOW_MS)

/** The add form after a failed add: the fields keep what was typed. */
fun sampleAddFields() = AddFormFields(TextFieldState("https://example.invalid/harbor"), TextFieldState("harbor"))

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SubscriptionsScreenPreview() {
    DoonaTheme { SubscriptionsScreen(sampleSubscriptionsState(), AddFormFields(), onAction = {}, onBack = {}) }
}

@Preview(name = "Add error", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun SubscriptionsAddPreview() {
    val state = sampleSubscriptionsState(adding = true, form = AddFormState(attempted = true, error = ErrorCode.FetchFailed))
    DoonaTheme { SubscriptionsScreen(state, sampleAddFields(), onAction = {}, onBack = {}) }
}
