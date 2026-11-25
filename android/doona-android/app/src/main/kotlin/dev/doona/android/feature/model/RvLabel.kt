package dev.doona.android.feature.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource

/**
 * A name a page shows: a string resource, as the samples and doona's own names give it, or the text a profile gives,
 * such as a policy group's name, which no resource can hold.
 */
@Immutable
internal sealed interface RvLabel {
    @JvmInline
    value class Res(@param:StringRes val id: Int) : RvLabel

    @JvmInline
    value class Text(val text: String) : RvLabel
}

/** The label as the page shows it. */
@Composable
internal fun RvLabel.text(): String = when (this) {
    is RvLabel.Res -> stringResource(id)
    is RvLabel.Text -> text
}
