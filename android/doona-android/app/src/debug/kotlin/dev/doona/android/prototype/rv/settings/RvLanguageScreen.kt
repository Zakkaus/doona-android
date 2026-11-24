package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme

/** The app's languages, each named in itself, as a language list names them; [System] follows the phone's. */
internal enum class RvLanguage(@StringRes val label: Int) {
    System(R.string.rv_settings_theme_system),
    English(R.string.rv_language_english),
    TraditionalChinese(R.string.rv_language_zh_hant),
    SimplifiedChinese(R.string.rv_language_zh_hans),
}

/**
 * 語言, the page 外觀's 語言 row opens, in DNS's language: the large title, then one card of radio rows, 跟隨系統 over
 * the language the phone's setting gives ([system]) and each language in its own name. No section title: the page's
 * title names the list, as the licences page's does.
 */
@Composable
internal fun RvLanguageScreen(selected: RvLanguage, system: RvLanguage, onSelect: (RvLanguage) -> Unit, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_language)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            RvSettingsRadioCard {
                RvLanguage.entries.forEach {
                    val subtitle = if (it == RvLanguage.System) stringResource(system.label) else null
                    RvSettingsRadioRow(stringResource(it.label), it == selected, { onSelect(it) }, subtitle = subtitle)
                }
            }
        }
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvLanguageScreenPreview() {
    val dark = isSystemInDarkTheme()
    var selected by remember { mutableStateOf(RvLanguage.TraditionalChinese) }
    RvTheme(rvSystemScheme(dark), dark) { RvLanguageScreen(selected, RvLanguage.TraditionalChinese, { selected = it }, onBack = {}) }
}
