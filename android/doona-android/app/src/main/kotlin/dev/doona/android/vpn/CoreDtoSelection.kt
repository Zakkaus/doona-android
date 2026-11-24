package dev.doona.android.vpn

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class SelectionNetwork {
    @SerialName("tcp")
    Tcp,

    @SerialName("udp")
    Udp,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SelectArgs(
    val group: String,
    val memberId: String?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val network: SelectionNetwork? = null,
)
