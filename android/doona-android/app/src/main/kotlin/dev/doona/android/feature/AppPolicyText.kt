package dev.doona.android.feature

import androidx.annotation.StringRes
import dev.doona.android.R
import dev.doona.android.engine.AppPolicy

/** The localized name of a per-app policy, shown by the 依 App page and by the 設定 row that opens it. */
@StringRes
fun AppPolicy.labelRes(): Int = when (this) {
    AppPolicy.All -> R.string.perapp_policy_all
    AppPolicy.OnlyListed -> R.string.perapp_policy_only
    AppPolicy.ExceptListed -> R.string.perapp_policy_except
}
