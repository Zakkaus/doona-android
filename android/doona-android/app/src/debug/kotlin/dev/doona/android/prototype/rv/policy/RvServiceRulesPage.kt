package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.policies.rvServiceRules

/** The services ticked in the 新增服務規則 sample. */
internal val RV_SERVICE_SAMPLE: Set<String> = setOf("youtube", "netflix")

/** 新增服務規則 ([dev.doona.android.feature.policies.RvServiceRulesPage]) on the sample: its main node and rules. */
@Composable
internal fun RvServiceRulesPage(
    onBack: () -> Unit,
    selected: Set<String> = RV_SERVICE_SAMPLE,
    onToggle: (String) -> Unit = {},
    onOutbound: () -> Unit = {},
) = dev.doona.android.feature.policies.RvServiceRulesPage(RvPolicyData.main, rvServiceRules(RvPolicyData.allRules), selected, onBack, onToggle, onOutbound)

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvServiceRulesPagePreview() {
    val dark = isSystemInDarkTheme()
    var selected by remember { mutableStateOf(RV_SERVICE_SAMPLE) }
    RvTheme(rvSystemScheme(dark), dark) {
        RvServiceRulesPage(onBack = {}, selected = selected, onToggle = { selected = if (it in selected) selected - it else selected + it })
    }
}
