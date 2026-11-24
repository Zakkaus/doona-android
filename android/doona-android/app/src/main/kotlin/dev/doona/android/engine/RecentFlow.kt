package dev.doona.android.engine

/** App-side endpoints and a start offset from tunnel start, measured on the boot clock. */
data class RecentFlow(
    val protocol: String,
    val local: String,
    val remote: String,
    val name: String?,
    val outbound: String,
    val startedMs: Long,
    val open: Boolean,
)
