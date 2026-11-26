package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.StaticNodeReason
import dev.doona.android.engine.StaticNodeResult
import dev.doona.android.feature.activity.placeOfRegion
import dev.doona.android.feature.model.RvNode

/**
 * One line of 新增節點's field that holds something: its 1-based [line] in the field, and the [link] trimmed. Blank lines
 * are left out but still count, so 第 4 行 is the fourth line the user sees.
 */
internal data class AddNodeLine(val line: Int, val link: String) {
    /** Whether the line can be a share link at all (`scheme://…`); only those go to the core. */
    val linkLike: Boolean get() = SCHEME.containsMatchIn(link)
}

/** The field's lines that hold something, at most [ADD_NODE_LINES] of them, as doona web's batch takes them. */
internal fun addNodeLines(text: String): List<AddNodeLine> =
    filledLines(text).take(ADD_NODE_LINES)

/** How many lines that hold something come after the first [ADD_NODE_LINES]: the add does not read them. */
internal fun addNodeUnread(text: String): Int = (filledLines(text).size - ADD_NODE_LINES).coerceAtLeast(0)

private fun filledLines(text: String): List<AddNodeLine> =
    text.lines().mapIndexedNotNull { i, raw -> raw.trim().takeIf { it.isNotEmpty() }?.let { AddNodeLine(i + 1, it) } }

/**
 * The lines that will not be added, by why: not a share link, already in 組態內, refused by the core for another reason,
 * and how many past the first [ADD_NODE_LINES] were not read ([unread]; [count] leaves them out, as they have no number).
 */
@Immutable
internal data class RvSkipped(
    val notLink: List<Int> = emptyList(),
    val duplicate: List<Int> = emptyList(),
    val other: List<Int> = emptyList(),
    val unread: Int = 0,
) {
    val count: Int get() = notLink.size + duplicate.size + other.size
}

/**
 * What 新增節點's field says about what was pasted: nothing yet ([None]), the one node it will add by the name the core
 * gives it ([One]), how many it will add and which lines it skips ([Many]), that nothing can be added ([Refused]), or that
 * the core could not be asked ([Failed]).
 */
internal sealed interface RvAddNodeCheck {
    data object None : RvAddNodeCheck

    data class One(val name: String) : RvAddNodeCheck

    data class Many(val count: Int, val skipped: RvSkipped) : RvAddNodeCheck

    data class Refused(val skipped: RvSkipped) : RvAddNodeCheck

    data class Failed(val code: ErrorCode) : RvAddNodeCheck

    /** Whether 新增 can run on it. */
    val addable: Boolean get() = this is One || this is Many
}

/**
 * The check of [lines] once the core has previewed the share links among them ([results], in the order of the lines that
 * look like links): one line the core takes is that node; otherwise the count of nodes it takes and the lines it skips,
 * with the [unread] lines past the cap.
 */
internal fun addNodeCheck(lines: List<AddNodeLine>, results: List<StaticNodeResult>, unread: Int = 0): RvAddNodeCheck {
    if (lines.isEmpty()) return RvAddNodeCheck.None
    val (ready, rows) = sortRows(lines, results)
    val skipped = rows.copy(unread = unread)
    return when {
        ready.isEmpty() -> RvAddNodeCheck.Refused(skipped)
        lines.size == 1 -> RvAddNodeCheck.One(ready.single().name)
        else -> RvAddNodeCheck.Many(ready.size, skipped)
    }
}

/** A row the core takes: its id and name, from a preview's Ready or an add's Added. */
internal data class TakenRow(val id: String, val name: String)

/** [lines] split into the rows the core takes and the lines it skips, matching [results] to the link-like lines in order. */
internal fun sortRows(lines: List<AddNodeLine>, results: List<StaticNodeResult>): Pair<List<TakenRow>, RvSkipped> {
    val taken = mutableListOf<TakenRow>()
    val notLink = mutableListOf<Int>()
    val duplicate = mutableListOf<Int>()
    val other = mutableListOf<Int>()
    val answers = results.iterator()
    lines.forEach { line ->
        val result = if (line.linkLike && answers.hasNext()) answers.next() else null
        when (result) {
            null -> if (line.linkLike) other += line.line else notLink += line.line
            is StaticNodeResult.Ready -> taken += TakenRow(result.id, result.name)
            is StaticNodeResult.Added -> taken += TakenRow(result.id, result.name)
            is StaticNodeResult.Duplicate -> duplicate += line.line
            is StaticNodeResult.Invalid -> if (result.reason.notALink()) notLink += line.line else other += line.line
            else -> other += line.line
        }
    }
    return taken to RvSkipped(notLink, duplicate, other)
}

/** The core's reasons that mean the line is no share link at all, as doona web's `nodes.linkInvalid` reads them. */
private fun StaticNodeReason.notALink() = this == StaticNodeReason.Link || this == StaticNodeReason.Protocol

/**
 * A group the added nodes joined, as the app names it: the main group, 自動, 串流 and AI by the app's own words, a region
 * group by its place, and any other group (one the user wrote into the configuration) by its own name.
 */
internal sealed interface RvGroupName {
    data object Main : RvGroupName

    data object Auto : RvGroupName

    data object Streaming : RvGroupName

    data object Ai : RvGroupName

    data class Place(val place: RvPlace) : RvGroupName

    data class Named(val name: String) : RvGroupName
}

/** [group]'s name on 訂閱; [mainGroupId] is the catalog's main group. */
internal fun rvGroupName(group: Group, mainGroupId: String): RvGroupName = when {
    group.id == mainGroupId -> RvGroupName.Main
    group.name == "Auto" -> RvGroupName.Auto
    group.name == "Streaming" -> RvGroupName.Streaming
    group.name == "AI" -> RvGroupName.Ai
    else -> placeOfRegion(group.name)?.let(RvGroupName::Place) ?: RvGroupName.Named(group.name)
}

/** The groups of [catalog] that hold any of [ids], in the catalog's order. */
internal fun joinedGroups(catalog: Catalog, ids: Set<String>): List<RvGroupName> =
    catalog.groups.filter { group -> group.members.any { it.id in ids } }.map { rvGroupName(it, catalog.mainGroupId) }

/** The IDs of [catalog]'s nodes that no subscription brought: the ones added by hand, the 組態內 source. */
internal fun inlineIds(catalog: Catalog?): Set<String> =
    catalog?.groups.orEmpty().flatMap { it.members }.filter { !it.isGroup && it.subscriptionId == null }.mapTo(LinkedHashSet()) { it.id }

/** After an add: how many nodes it added, the groups they joined, and the lines the preview took but the add refused. */
@Immutable
internal data class RvAddedNodes(val count: Int, val groups: List<RvGroupName>, val skipped: RvSkipped)

/** A node the core would not delete: its name for the banner and why. */
@Immutable
internal data class RvNodeDeleteFailed(val id: String, val name: String, val code: ErrorCode)

/**
 * The 組態內 source on 訂閱: its [nodes]; the add sheet while it is open ([sheet]); what the last add did ([added]); the
 * node asked to delete ([deletingId]) and the last delete the core refused ([deleteFailed]).
 */
@Immutable
internal data class RvInlineState(
    val nodes: List<RvNode> = emptyList(),
    val sheet: RvAddNodeSheet? = null,
    val added: RvAddedNodes? = null,
    val deletingId: String? = null,
    val deleteFailed: RvNodeDeleteFailed? = null,
)

/** 新增節點's sheet: the field's [text], whether the clipboard may hold a link, what the field says, and an add running. */
@Immutable
internal data class RvAddNodeSheet(
    val text: String = "",
    val clipboard: Boolean = false,
    val check: RvAddNodeCheck = RvAddNodeCheck.None,
    val adding: Boolean = false,
)

/** The most lines one add takes, as the core's batch (`static_nodes.rs`, 1 to 200 rows) and doona web's. */
internal const val ADD_NODE_LINES = 200

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
