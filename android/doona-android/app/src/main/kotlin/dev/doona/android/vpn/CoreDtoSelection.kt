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
    /** The core's name for the kind; decoded by [GroupKind.of], so an unknown one keeps the rest of the catalog. */
    val kind: String,
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
    fun toGroup(): Group {
        val tcp = Group.Selection(tcpSelectedId, tcpPinned)
        val udp = Group.Selection(udpSelectedId, udpPinned)
        return Group(id, name, GroupKind.of(kind), members.map(MemberDto::toMember), tcp, udp, testing)
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
