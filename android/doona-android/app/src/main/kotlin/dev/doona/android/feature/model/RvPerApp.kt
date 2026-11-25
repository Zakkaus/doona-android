package dev.doona.android.feature.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R

/** Which apps the tunnel takes (the engine's `AppPolicy`): all of them, only the checked ones, or all but those. */
internal enum class RvAppPolicy(@StringRes val label: Int) {
    All(R.string.perapp_policy_all),
    Only(R.string.perapp_policy_only),
    Except(R.string.perapp_policy_except),
}

/** A per-app setting: the [policy] and the checked [packages], which it keeps even for an app no longer installed. */
@Immutable
internal data class RvPerApp(val policy: RvAppPolicy, val packages: Set<String>)
