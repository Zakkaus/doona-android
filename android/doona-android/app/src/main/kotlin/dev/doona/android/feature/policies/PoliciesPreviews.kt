package dev.doona.android.feature.policies

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.fake.FakeEngine

/** [FakeEngine.SAMPLE_GROUPS] where the node jp-01 failed its last probe, and Auto is probing again. */
val SAMPLE_TESTING_GROUPS: List<Group> = FakeEngine.SAMPLE_GROUPS.map { group ->
    val members = group.members.map { if (it.id == "n-jp1") it.copy(latencyMs = null, failed = true) else it }
    group.copy(members = members, testing = group.id == "g-Auto")
}

/** The Auto group of [SAMPLE_TESTING_GROUPS] with the user's choice, jp-01, pinned over its own. */
val SAMPLE_PINNED_GROUPS: List<Group> = SAMPLE_TESTING_GROUPS.map {
    if (it.id == "g-Auto") it.copy(selectedId = "n-jp1", pinned = true) else it
}

fun samplePoliciesState(groups: List<Group> = FakeEngine.SAMPLE_GROUPS, error: ErrorCode? = null): PoliciesUiState =
    policiesUiState(true, groups, GroupFilter.All, error)

/** The page of [groupId] among [groups], as previews and screenshot tests show it. */
fun sampleGroupDetail(groupId: String, groups: List<Group> = FakeEngine.SAMPLE_GROUPS): GroupDetail =
    requireNotNull(groupUiState(true, groups, groupId, null).detail) { "no group $groupId in the sample" }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PoliciesScreenPreview() {
    DoonaTheme { PoliciesScreen(samplePoliciesState(), onAction = {}, onOpenGroup = {}, onOpenSubscriptions = {}) }
}

@Preview(name = "Testing", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun PoliciesScreenTestingPreview() {
    DoonaTheme { PoliciesScreen(samplePoliciesState(SAMPLE_TESTING_GROUPS, ErrorCode.Busy), onAction = {}, onOpenGroup = {}, onOpenSubscriptions = {}) }
}

@Preview(name = "Pinned", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun GroupScreenPinnedPreview() {
    val detail = sampleGroupDetail("g-Auto", SAMPLE_PINNED_GROUPS)
    DoonaTheme { GroupScreen(detail, error = null, onAction = {}, onOpenGroup = {}, onBack = {}) }
}
