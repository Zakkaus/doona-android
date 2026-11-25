package dev.doona.android.designsystem.rv

import androidx.compose.runtime.Immutable

/** A prompt's words, shared by the sheet and its golden. */
@Immutable
internal data class RvPromptText(val title: String, val body: String, val confirm: String, val dismiss: String)
