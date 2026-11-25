package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvLatencyText
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.rvScorePick
import dev.doona.android.feature.nodes.ui.RvScoreCard

/**
 * How a Score group chooses for its two networks (a sample; honk's `score_network_mode`, joint by default): [separate]
 * is whether 分開選擇 TCP 與 UDP is on, and in joint mode [fallback] is why UDP left TCP's node, or null while UDP follows
 * it.
 */
@Immutable
internal class RvScoreJoint(val separate: Boolean, val fallback: RvUdpFallback? = null)

/** Why UDP does not use the node TCP chose in joint mode. */
internal enum class RvUdpFallback {
    /** TCP's node cannot carry UDP. */
    Unsupported,

    /** UDP's test on TCP's node did not pass. */
    Failed,
}

/**
 * A Score group's cards with its network mode: first the switch 分開選擇 TCP 與 UDP in a card of its own ([onSeparate]).
 * Separate: a card per network, as on the page without the switch ([RvScoreCard]). Joint, UDP on TCP's node: one card
 * headed TCP 與 UDP, TCP's comparison (UDP follows TCP's choice). Joint, UDP elsewhere: TCP's card, then UDP's node
 * with why it is not TCP's.
 */
@Composable
internal fun RvScoreJointCards(score: ScoreGroup, joint: RvScoreJoint, members: List<RvNode>, onSeparate: (Boolean) -> Unit) {
    RvCard { RvSwitchRow(R.drawable.rv_sym_call_split, stringResource(R.string.rv_network_apart), joint.separate, onChange = onSeparate) }
    val tcp = stringResource(R.string.ui_tcp)
    val fallback = joint.fallback
    when {
        joint.separate -> {
            RvScoreCard(tcp, rvScorePick(score.tcp), members)
            RvScoreCard(stringResource(R.string.ui_udp), rvScorePick(score.udp), members)
        }

        fallback == null -> RvScoreCard(stringResource(R.string.rv_network_both), rvScorePick(score.tcp), members)

        else -> {
            RvScoreCard(tcp, rvScorePick(score.tcp), members)
            UdpCard(members.first { it.id == score.udp.selectedId }, members.first { it.id == score.tcp.selectedId }, fallback)
        }
    }
}

/** UDP's node in joint mode when it is not TCP's: badged as a choice, its line why, its last test at the end. */
@Composable
private fun UdpCard(node: RvNode, tcpNode: RvNode, fallback: RvUdpFallback) {
    val why = when (fallback) {
        RvUdpFallback.Unsupported -> stringResource(R.string.rv_score_udp_unsupported, tcpNode.name)
        RvUdpFallback.Failed -> stringResource(R.string.rv_score_udp_failed)
    }
    RvCard {
        RvCardHeader(stringResource(R.string.ui_udp))
        RvRow(
            { RvFlag(node.place.flag, RvSize.rowDisc, current = true) },
            node.name,
            why,
            titleEnd = { RvLatencyText(node.latencyMs, node.timedOut) },
        )
    }
}
