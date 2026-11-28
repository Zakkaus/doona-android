package dev.doona.android.prototype.rv.samples

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLatencyText
import dev.doona.android.designsystem.rv.RvNameDisc
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvShownName
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rvMarkName
import dev.doona.android.designsystem.rv.rvShownName
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.nodes.ui.NODE_SEPARATOR
import dev.doona.android.prototype.rv.policy.RvPolicyData

/** The names the emoji1 sample shows, in node rows and in group rows, and the place a node of that name is in. */
internal val RV_EMOJI_NAMES: List<Pair<String, RvPlace>> = listOf(
    "🇭🇰 香港 01" to RvPlace.HK,
    "🇺🇸 US 02 ⚡" to RvPlace.US,
    "🍎 Apple" to RvPlace.TW,
    "🚀 節點選擇" to RvPlace.SG,
    "🇯🇵" to RvPlace.JP,
    "🦄 Unicorn 03" to RvPlace.TW,
)

/**
 * Node rows and group rows whose names carry emoji (emoji1), as today ([proposed] false: the emoji stay in the name, a
 * node's flag and a group's glyph lead, so a flag emoji makes two flags) and as proposed (the recognised emoji lead as
 * our flag or mark and leave the name). Built from the policy and node pages' rows; a name with nothing left is named by
 * its mark (the country, the brand).
 */
@Composable
internal fun RvEmojiNamesPage(proposed: Boolean, modifier: Modifier = Modifier) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = sampleText("名稱中的 emoji", "Emoji in names")
    RvScrollPage(title, insets, modifier, onBack = {}) { titleBlock ->
        RvPageHeader(title, titleBlock, insets)
        RvGutter {
            RvCard {
                RvCardHeader(stringResource(R.string.rv_policy_nodes))
                val nodes = RvPolicyData.streaming.byLatency
                RV_EMOJI_NAMES.forEachIndexed { index, (name, place) -> NodeNameRow(nodes[index].copy(name = name, place = place), proposed) }
            }
            RvCard {
                RvCardHeader(stringResource(R.string.rv_policy_groups))
                RV_EMOJI_NAMES.forEachIndexed { index, (name, _) -> GroupNameRow(name, if (index % 2 == 0) RvGroupKind.Select else RvGroupKind.Auto, proposed) }
            }
        }
    }
}

@Composable
private fun NodeNameRow(node: RvNode, proposed: Boolean) {
    val shown = if (proposed) rvShownName(node.name) else RvShownName(null, node.name)
    val place = stringResource(node.place.label)
    RvRow(
        { RvNameDisc(shown.mark, node.place.flag) },
        shown.text.ifEmpty { rvMarkName(shown.mark, place) },
        listOf(node.protocol, place).joinToString(NODE_SEPARATOR),
        titleEnd = { RvLatencyText(node.latencyMs, node.timedOut) },
        titleKeepWords = false,
    )
}

@Composable
private fun GroupNameRow(name: String, kind: RvGroupKind, proposed: Boolean) {
    val shown = if (proposed) rvShownName(name) else RvShownName(null, name)
    val count = pluralStringResource(R.plurals.rv_policy_node_count, GROUP_NODES, GROUP_NODES)
    RvRow(
        { if (shown.mark == null) RvIconDisc(kind.glyph) else RvNameDisc(shown.mark, null) },
        shown.text.ifEmpty { rvMarkName(shown.mark, "") },
        stringResource(kind.label) + SEPARATOR + count,
        titleKeepWords = false,
    )
}

private const val GROUP_NODES = 12
