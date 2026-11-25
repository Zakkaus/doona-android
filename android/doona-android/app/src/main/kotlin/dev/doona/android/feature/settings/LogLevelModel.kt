package dev.doona.android.feature.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.doona.android.R
import dev.doona.android.engine.LogLevel

/** A level the core logs at (diagnostics `setLogLevel`): its name, and its glyph. */
internal enum class RvLogLevel(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Error(R.string.rv_log_error, R.drawable.rv_sym_error),
    Warn(R.string.rv_log_warn, R.drawable.rv_nodes_ic_warning),
    Info(R.string.rv_log_info, R.drawable.rv_sym_info),
    Debug(R.string.rv_log_debug, R.drawable.rv_settings_ic_description),
}

/** How long 除錯 stays before the core logs at 資訊 again; the owner sets the time (brief: say 1 hour). */
internal const val RV_DEBUG_HOURS = 1

/** The page's level for the core's [level]. */
internal fun rvLogLevel(level: LogLevel): RvLogLevel = when (level) {
    LogLevel.Error -> RvLogLevel.Error
    LogLevel.Warn -> RvLogLevel.Warn
    LogLevel.Info -> RvLogLevel.Info
    LogLevel.Debug -> RvLogLevel.Debug
}

/** The core's level for the page's [level]. */
internal fun RvLogLevel.engineLevel(): LogLevel = when (this) {
    RvLogLevel.Error -> LogLevel.Error
    RvLogLevel.Warn -> LogLevel.Warn
    RvLogLevel.Info -> LogLevel.Info
    RvLogLevel.Debug -> LogLevel.Debug
}
