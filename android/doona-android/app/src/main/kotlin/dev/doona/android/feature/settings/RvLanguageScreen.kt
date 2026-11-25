package dev.doona.android.feature.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsRadioCard
import dev.doona.android.designsystem.rv.RvSettingsRadioRow
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.feature.model.RvLanguage

/** The language the phone's own setting gives the app: its first locale, as [RvLanguage.of] reads it; never [RvLanguage.System]. */
internal fun rvSystemLanguage(): RvLanguage = RvLanguage.of(Resources.getSystem().configuration.locales.toLanguageTags()).let {
    if (it == RvLanguage.System) RvLanguage.English else it
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
