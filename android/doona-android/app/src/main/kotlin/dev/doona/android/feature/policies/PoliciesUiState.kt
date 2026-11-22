package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.Group

/** What the 策略 screens show, in engine terms. The 策略 lane adds selection, latency tests and their errors. */
@Immutable
data class PoliciesUiState(
    val groups: List<Group> = emptyList(),
    /** False until the engine has loaded its groups; an empty list before that is not a removal. */
    val loaded: Boolean = true,
)
