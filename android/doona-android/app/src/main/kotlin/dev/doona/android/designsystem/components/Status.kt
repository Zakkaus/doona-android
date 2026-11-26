package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Tone
import dev.doona.android.designsystem.rv.latencyTone
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.DoonaToneInk
import dev.doona.android.designsystem.theme.scripted

fun toneColor(c: DoonaColors, tone: Tone): Color = when (tone) {
    Tone.Ok -> c.good
    Tone.Warn -> c.fair
    Tone.Err -> c.slow
    Tone.Neutral -> c.textSecondary
}
