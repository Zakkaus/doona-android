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
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.policies.RvRulesPage
import dev.doona.android.feature.policies.RvRulesRoutes
import dev.doona.android.feature.policies.RvTemplateImpactPage
import dev.doona.android.prototype.rv.settings.rvSampleSettings

// The previews of the 策略 pages, which moved to feature/policies; they draw the debug samples.

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvPolicyScreenPreview() {
    val dark = isSystemInDarkTheme()
    var state by remember { mutableStateOf(RvPolicyState()) }
    RvTheme(rvSystemScheme(dark), dark) { RvPolicyScreenSample(state, { state = it }) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvRulesPagePreview() {
    val dark = isSystemInDarkTheme()
    var editing by remember { mutableStateOf(false) }
    RvTheme(rvSystemScheme(dark), dark) {
        RvRulesPage(RvPolicyData.allRules, emptyList(), editing, onBack = {}, routes = RvRulesRoutes(edit = { editing = it }))
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTemplatePagePreview() {
    val dark = isSystemInDarkTheme()
    var selected by remember { mutableStateOf(RvTemplateData.cnDirect) }
    RvTheme(rvSystemScheme(dark), dark) { RvTemplatePageSample(RvTemplateData.cnDirect, selected, { selected = it }, onBack = {}, onReview = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTemplateImpactPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvTemplateImpactPage(RvTemplateData.impact, onBack = {}, onApply = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvEffectiveConfigPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvEffectiveConfigPageSample(RvPolicyState(), rvSampleSettings(), onBack = {}) }
}
