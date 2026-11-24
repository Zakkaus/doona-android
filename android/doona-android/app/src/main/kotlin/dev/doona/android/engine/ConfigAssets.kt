package dev.doona.android.engine

/** The active geo data and the last update state. [release] is an ISO date, or null for the built-in rules. */
data class ConfigAssets(
    val source: String,
    val release: String?,
    val lastResult: String,
    val reason: String?,
    val updateWaitingForNextStart: Boolean,
)
