package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.rvRowPress
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * The page 進階 → DNS opens (features-gap item 11, v0.2), in 主題's language: the large title, then one titled card of
 * servers, each its name over its address with a radio, and 自訂 last, which opens an address field (rv-pages-a's text
 * field, not wired here).
 */
@Composable
internal fun RvDnsScreen(selected: RvDnsUpstream, onSelect: (RvDnsUpstream) -> Unit, onBack: () -> Unit, onCustom: () -> Unit = NOT_WIRED) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_dns)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            // The first section sits on the header's gap, as 主題's does.
            RvSettingsSection(stringResource(R.string.rv_settings_dns_server), first = true)
            RvSettingsCard {
                RvDnsUpstream.entries.forEach { RvSettingsRadioRow(stringResource(it.label), it == selected, { onSelect(it) }, subtitle = it.address) }
                // No glyph, so its text lines up with the servers above it.
                RvSettingsRow(stringResource(R.string.rv_settings_dns_custom), Modifier.rvRowPress(Role.Button, onCustom), subtitle = stringResource(R.string.rv_settings_dns_custom_sub)) {
                    RvChevron()
                }
            }
        }
        Spacer(Modifier.height(RvSettingsSpace.bottom))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvDnsScreenPreview() {
    val dark = isSystemInDarkTheme()
    var selected by remember { mutableStateOf(RvDnsUpstream.Cloudflare) }
    RvTheme(rvSystemScheme(dark), dark) { RvDnsScreen(selected, { selected = it }, onBack = {}) }
}
