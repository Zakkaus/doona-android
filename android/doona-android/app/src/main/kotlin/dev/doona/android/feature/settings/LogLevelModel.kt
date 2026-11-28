package dev.doona.android.feature.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.doona.android.R

/**
 * A level honk logs at (`/runtime/settings` `log.level`, the name in lower case): its name, and its glyph. Public, as
 * the app-wide [dev.doona.android.data.settings.LogLevelController] holds it.
 */
enum class RvLogLevel(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Error(R.string.rv_log_error, R.drawable.rv_sym_error),
    Warn(R.string.rv_log_warn, R.drawable.rv_nodes_ic_warning),
    Info(R.string.rv_log_info, R.drawable.rv_sym_info),
    Debug(R.string.rv_log_debug, R.drawable.rv_settings_ic_description),
}

/** How long 除錯 stays before the core logs at 資訊 again; the owner sets the time (brief: say 1 hour). */
internal const val RV_DEBUG_HOURS = 1
