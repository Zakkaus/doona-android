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
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLatencyText
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.policy.RvGroupKind
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
        { Mark(shown.mark) { RvFlag(node.place.flag, RvSize.rowDisc) } },
        shown.text.ifEmpty { markName(shown.mark, place) },
        listOf(node.protocol, place).joinToString(SEPARATOR),
        titleEnd = { RvLatencyText(node.latencyMs, node.timedOut) },
        titleKeepWords = false,
    )
}

@Composable
private fun GroupNameRow(name: String, kind: RvGroupKind, proposed: Boolean) {
    val shown = if (proposed) rvShownName(name) else RvShownName(null, name)
    val count = pluralStringResource(R.plurals.rv_policy_node_count, GROUP_NODES, GROUP_NODES)
    RvRow(
        { Mark(shown.mark) { RvIconDisc(kind.glyph) } },
        shown.text.ifEmpty { markName(shown.mark, "") },
        stringResource(kind.label) + SEPARATOR + count,
        titleKeepWords = false,
    )
}

/** The row's leading disc: the name's recognised mark as our flag or glyph, else what the row leads with today. */
@Composable
private fun Mark(mark: RvNameMark?, today: @Composable () -> Unit) {
    when (mark) {
        is RvNameMark.Flag -> RvFlag(mark.code, RvSize.rowDisc)
        is RvNameMark.Icon -> RvIconDisc(mark.glyph)
        null -> today()
    }
}

/** What a name with nothing left but its emoji reads: the flag's country ([place]), or the brand or group. */
@Composable
private fun markName(mark: RvNameMark?, place: String): String = when (mark) {
    is RvNameMark.Flag -> RvPlace.entries.firstOrNull { it.flag.equals(mark.code, ignoreCase = true) }?.let { stringResource(it.label) } ?: place
    is RvNameMark.Icon -> sampleText(mark.zh, mark.en)
    null -> place
}

private const val GROUP_NODES = 12
