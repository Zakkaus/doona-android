package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable

/** The network a tap on a node applies to while 分開選擇 TCP 與 UDP is on, in the control's order (nborder). */
internal enum class RvApplyTo { Both, Tcp, Udp }

/**
 * A selector group's choice per network for the sample of the 套用到 control (nbtap): the node TCP uses ([tcpId]), the
 * node UDP uses ([udpId]), and whether 分開選擇 TCP 與 UDP is on ([apart]). Off, the two networks use one node.
 */
@Immutable
internal class RvApplyChoice(val tcpId: String, val udpId: String, val apart: Boolean) {
    /** Whether [nodeId] is the choice of either network. */
    fun chosen(nodeId: String): Boolean = nodeId == tcpId || nodeId == udpId

    /** A tap on [nodeId] with 套用到 at [apply]: no menu, the node goes to the selected network. Off, it goes to both. */
    fun tapped(nodeId: String, apply: RvApplyTo): RvApplyChoice = when {
        !apart -> RvApplyChoice(nodeId, nodeId, false)
        apply == RvApplyTo.Tcp -> RvApplyChoice(nodeId, udpId, true)
        apply == RvApplyTo.Udp -> RvApplyChoice(tcpId, nodeId, true)
        else -> RvApplyChoice(nodeId, nodeId, true)
    }

    /** The switch set to [on]; off, UDP goes back to TCP's node (nboff). */
    fun apart(on: Boolean): RvApplyChoice = if (on) RvApplyChoice(tcpId, udpId, true) else RvApplyChoice(tcpId, tcpId, false)
}
