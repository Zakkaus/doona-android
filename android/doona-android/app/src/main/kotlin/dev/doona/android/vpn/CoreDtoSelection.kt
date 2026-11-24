package dev.doona.android.vpn

import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GroupDto(
    val id: String,
    val name: String,
    val kind: GroupKind,
    val selectedId: String? = null,
    val tcpSelectedId: String? = selectedId,
    val udpSelectedId: String? = selectedId,
    val networksDiffer: Boolean = false,
    val pinned: Boolean,
    val tcpPinned: Boolean = pinned,
    val udpPinned: Boolean = pinned,
    val testing: Boolean,
    val members: List<MemberDto>,
) {
    fun toGroup() = Group(id, name, kind, members.map(MemberDto::toMember), selectedId, pinned, testing)
}

@Serializable
internal enum class SelectionNetwork {
    @SerialName("tcp")
    Tcp,

    @SerialName("udp")
    Udp,
}

@Serializable
internal data class SelectArgs(
    val group: String,
    val memberId: String?,
    val network: SelectionNetwork? = null,
)
