package dev.doona.android.feature.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvInputContent
import dev.doona.android.designsystem.rv.RvInputSheet
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvSettingsRadioRow
import dev.doona.android.designsystem.rv.RvSettingsRow
import dev.doona.android.designsystem.rv.RvSettingsSection
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvRowPress
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvDnsUpstream

/**
 * The page 進階 → DNS opens, in 主題's language: the large title, then one titled card of servers, each its name over
 * its address with a radio, and 自訂 last, which opens [RvDnsCustomSheet]; a footer says the query goes out through the
 * main node (rule-mode decision 10). A [selected] null (servers no preset names) leaves every radio empty.
 */
@Composable
internal fun RvDnsScreen(selected: RvDnsUpstream?, onSelect: (RvDnsUpstream) -> Unit, onBack: () -> Unit, onCustom: () -> Unit = NOT_WIRED) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_dns)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvSettingsColumn {
            // The first section sits on the header's gap, as 主題's does.
            RvSettingsSection(stringResource(R.string.rv_settings_dns_server), first = true)
            RvSettingsCard {
                RvDnsUpstream.entries.forEach { RvSettingsRadioRow(stringResource(it.label), it == selected, { onSelect(it) }, subtitle = it.address, subtitleKeepWords = false) }
                // No glyph, so its text lines up with the servers above it.
                RvSettingsRow(stringResource(R.string.rv_settings_dns_custom), Modifier.rvRowPress(Role.Button, onCustom), subtitle = stringResource(R.string.rv_settings_dns_custom_sub)) {
                    RvChevron()
                }
            }
            RvFootnote(stringResource(R.string.rv_settings_dns_footer))
        }
    }
}

/** DNS in the app: the core's servers, a tap on a preset saves it, and 自訂 opens the sheet that saves typed ones. */
@Composable
internal fun DnsRoute(engine: Engine, onBack: () -> Unit) {
    val viewModel: DnsViewModel = viewModel(factory = DnsViewModel.factory(engine))
    val state = viewModel.uiState.collectAsStateWithLifecycle().value ?: return RvSettingsLoading()
    RvDnsScreen(rvDnsUpstream(state.servers), viewModel::select, onBack, onCustom = viewModel::openCustom)
    RvDnsCustomSheet(state, viewModel::editCustom, viewModel::saveCustom, viewModel::dismissCustom)
}

/**
 * 自訂: a sheet titled by what it sets, one field for 1 to 4 addresses separated by commas, with a preset's two as its
 * placeholder, and 儲存 and 取消. A save the core refuses keeps the sheet open with why under the field.
 */
@Composable
internal fun RvDnsCustomSheet(state: DnsUiState, onText: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val (title, hint, error) = customWords(state)
    RvInputSheet(state.customOpen, title, state.customText, onText, onSave, onDismiss, PLACEHOLDER, hint, error, KeyboardType.Uri)
}

/** What [RvDnsCustomSheet] shows in its card. */
@Composable
internal fun ColumnScope.RvDnsCustomContent(state: DnsUiState, onText: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val (title, hint, error) = customWords(state)
    RvInputContent(title, state.customText, onText, onSave, onDismiss, PLACEHOLDER, hint, error, KeyboardType.Uri)
}

/** 自訂's title, hint and, once a save failed, why. */
@Composable
private fun customWords(state: DnsUiState): Triple<String, String, String?> = Triple(
    stringResource(R.string.rv_settings_dns_custom_title),
    stringResource(R.string.rv_settings_dns_custom_hint),
    state.customError?.let { stringResource(if (it == ErrorCode.InvalidArgument) R.string.rv_settings_dns_custom_invalid else it.messageRes()) },
)

/** 自訂's placeholder: two presets' addresses, the form the field takes. */
private val PLACEHOLDER = "${RvDnsUpstream.Cloudflare.address}, ${RvDnsUpstream.Google.address}"

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvDnsScreenPreview() {
    val dark = isSystemInDarkTheme()
    var selected by remember { mutableStateOf(RvDnsUpstream.Cloudflare) }
    RvTheme(rvSystemScheme(dark), dark) { RvDnsScreen(selected, { selected = it }, onBack = {}) }
}
