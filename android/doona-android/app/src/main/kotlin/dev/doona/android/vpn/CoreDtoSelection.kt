package dev.doona.android.vpn

import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Network
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GroupDto(
    val id: String,
    val name: String,
    val kind: GroupKind,
    /** On the wire for the round-trip test; the app reads the per-network fields. */
    val selectedId: String?,
    val tcpSelectedId: String?,
    val udpSelectedId: String?,
    val networksDiffer: Boolean = false,
    val pinned: Boolean,
    val tcpPinned: Boolean,
    val udpPinned: Boolean,
    val testing: Boolean,
    val members: List<MemberDto>,
) {
    fun toGroup(): Group {
        val tcp = Group.Selection(tcpSelectedId, tcpPinned)
        val udp = Group.Selection(udpSelectedId, udpPinned)
        return Group(id, name, kind, members.map(MemberDto::toMember), tcp, udp, testing)
    }
}

@Serializable
internal enum class SelectionNetwork {
    @SerialName("tcp")
    Tcp,

    @SerialName("udp")
    Udp,
    ;

    companion object {
        fun of(network: Network?): SelectionNetwork? = when (network) {
            Network.Tcp -> Tcp
            Network.Udp -> Udp
            null -> null
        }
    }
}

@Serializable
internal data class SelectArgs(
    val group: String,
    val memberId: String?,
    val network: SelectionNetwork? = null,
)
