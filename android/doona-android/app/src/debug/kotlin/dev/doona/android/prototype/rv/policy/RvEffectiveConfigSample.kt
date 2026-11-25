package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.settings.RvSettingsState
import dev.doona.android.share.rememberEffectiveConfigActions

/**
 * 檢視生效設定 in the preview: 複製 and 匯出 act on the text the RV sample engine gives for `effectiveConfig()`, the
 * same text the fake core serves, so the page lane only swaps the engine.
 */
@Composable
internal fun RvEffectiveConfigSample(policy: RvPolicyState, settings: RvSettingsState, onBack: () -> Unit, onRules: () -> Unit) {
    val text by produceState<String?>(null) { value = rvSampleEngine().effectiveConfig().getOrNull()?.text }
    val actions = rememberEffectiveConfigActions(stringResource(R.string.rv_effective_title), text)
    RvEffectiveConfigPage(policy, settings, onBack = onBack, onRules = onRules, onCopy = actions.copy, onExport = actions.export)
}
