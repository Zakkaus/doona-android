package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Two actions of one choice, stacked, the main one ([primary], [RvPrimaryButton]) over the other ([secondary], the
 * neutral [RvSecondaryButton]), [RvSpace.controlStack] apart. Both are the main pill's full width, its
 * [RvControlSize.primary] height and its label size, so the pair reads as one part and neither looks lesser (owner
 * 2026-11-24, 回報問題's pair). Revolut's large pill is the measured 52 dp (UX3 §2, button heights); its in-card 40 dp pill stays for a
 * card's or a banner's one action. [enabled] holds both; floating over a page, [over] is the page's colour (see
 * [RvSecondaryButton]).
 */
@Composable
internal fun RvButtonPair(
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    over: Color? = null,
) {
    Column(modifier.fillMaxWidth()) {
        RvPrimaryButton(primary, onPrimary, enabled = enabled)
        Spacer(Modifier.height(RvSpace.controlStack))
        RvSecondaryButton(secondary, onSecondary, enabled = enabled, over = over, height = RvControlSize.primary, labelStyle = Rv.type.button)
    }
}
