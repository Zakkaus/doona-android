package dev.doona.android.vpn

import kotlinx.serialization.Serializable

@Serializable
internal data class ConfigAssetsArgs(val action: String)

@Serializable
internal data class ConfigAssetsDto(
    val source: String,
    val release: String?,
    val lastResult: String,
    val reason: String?,
    val updateWaitingForNextStart: Boolean,
)
