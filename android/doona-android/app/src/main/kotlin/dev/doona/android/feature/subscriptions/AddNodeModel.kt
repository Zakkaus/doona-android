package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.isInline
import dev.doona.android.honk.Group
import dev.doona.android.honk.HonkSource
import dev.doona.android.honk.Node
import dev.doona.android.honk.Provider
import dev.doona.android.honk.daeGroup
import java.net.URI
import java.net.URLDecoder

/**
 * One line of 新增節點's field that holds something: its 1-based [line] in the field, and the [link] trimmed. Blank lines
 * are left out but still count, so 第 4 行 is the fourth line the user sees.
 */
internal data class AddNodeLine(val line: Int, val link: String) {
    /** Whether the line can be a share link at all (`scheme://…`); only those go to honk. */
    val linkLike: Boolean get() = SCHEME.containsMatchIn(link)

    /**
     * A display name the link supplies: a fragment or a host. A bare authority can be encoded credentials.
     */
    val name: String?
        get() {
            val first = link.substringBefore("->")
            val fragment = first.substringAfter('#', "").let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.trim()
            return fragment.ifEmpty {
                when (first.substringBefore(':').lowercase()) {
                    "ss", "vmess", "vless" -> runCatching { URI(first) }.getOrNull()?.takeIf { it.rawUserInfo != null || it.port != -1 }?.host.orEmpty()
                    "trojan", "socks", "socks5", "http", "https", "hysteria2", "hy2", "tuic" -> subscriptionHost(first)
                    else -> ""
                }
            }.take(NODE_NAME_MAX).ifEmpty { null }
        }
}

/** The field's lines that hold something, at most [ADD_NODE_LINES] of them, as doona web's batch takes them. */
internal fun addNodeLines(text: String): List<AddNodeLine> =
    filledLines(text).take(ADD_NODE_LINES)

/** How many lines that hold something come after the first [ADD_NODE_LINES]: the add does not read them. */
internal fun addNodeUnread(text: String): Int = (filledLines(text).size - ADD_NODE_LINES).coerceAtLeast(0)

private fun filledLines(text: String): List<AddNodeLine> =
    text.lines().mapIndexedNotNull { i, raw -> raw.trim().takeIf { it.isNotEmpty() }?.let { AddNodeLine(i + 1, it) } }

/**
 * The lines that will not be added, by why: not a share link, duplicate name, refused by honk for another
 * reason, and how many past the first [ADD_NODE_LINES] were not read ([unread]; [count] leaves them out, as they have
 * no number).
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
 * What 新增節點's field says about what was pasted: nothing yet ([None]), the one node it will add by the name it files
 * it under ([One]), how many it will send and which lines it skips ([Many]), or that nothing can be added ([Refused]).
 */
internal sealed interface RvAddNodeCheck {
    data object None : RvAddNodeCheck

    data class One(val name: String) : RvAddNodeCheck

    data class Many(val count: Int, val skipped: RvSkipped) : RvAddNodeCheck

    data class Refused(val skipped: RvSkipped) : RvAddNodeCheck

    data class Failed(val code: ErrorCode) : RvAddNodeCheck

    /** Whether 新增 can run on it. */
    val addable: Boolean get() = this is One || this is Many || this is Failed
}

/**
 * [lines] split into the ones an add sends and the lines it skips: no share link, or a name honk would refuse as in use
 * ([names] in the catalog, or a line above with the same name). honk reads each link only when it is added.
 */
internal fun addNodeRows(lines: List<AddNodeLine>, names: Set<String>): Pair<List<Pair<AddNodeLine, String>>, RvSkipped> {
    val taken = names.toMutableSet()
    val sent = mutableListOf<Pair<AddNodeLine, String>>()
    val notLink = mutableListOf<Int>()
    val duplicate = mutableListOf<Int>()
    val candidates = lines.mapNotNull { line ->
        if (line.linkLike) return@mapNotNull line to line.name
        notLink += line.line
        null
    }
    val explicitNames = candidates.mapNotNullTo(HashSet()) { it.second }
    val anonymous = HashSet<String>()
    var next = 1
    for ((line, explicit) in candidates) {
        var name = explicit
        if (name == null) {
            if (!anonymous.add(line.link)) {
                duplicate += line.line
                continue
            }
            do {
                name = "node-${next++}"
            } while (name in taken || name in explicitNames)
        }
        if (taken.add(name)) sent += line to name else duplicate += line.line
    }
    return sent to RvSkipped(notLink, duplicate)
}

/** The check of [lines], [names] being 組態內's: one line sent is that node; otherwise the count and the lines skipped. */
internal fun addNodeCheck(lines: List<AddNodeLine>, names: Set<String>, unread: Int = 0): RvAddNodeCheck {
    if (lines.isEmpty()) return RvAddNodeCheck.None
    val (sent, rows) = addNodeRows(lines, names)
    val skipped = rows.copy(unread = unread)
    return when {
        sent.isEmpty() -> RvAddNodeCheck.Refused(skipped)
        lines.size == 1 -> RvAddNodeCheck.One(sent.single().second)
        else -> RvAddNodeCheck.Many(sent.size, skipped)
    }
}

/** The names of honk's [groups] that hold any of [ids], in honk's order and as honk has them. */
internal fun joinedGroups(groups: List<Group>, ids: Set<String>): List<String> =
    groups.filter { group -> group.members.any { it.id in ids } }.map { it.name }

/** honk's [nodes] that no subscription or file brought: the ones added by their link, the 組態內 source. */
internal fun inlineNodes(nodes: List<Node>, providers: List<Provider>): List<Node> {
    val byId = providers.associateBy { it.id }
    return nodes.filter { it.isInline(byId) }
}

/**
 * After an add: how many nodes it added, the groups they joined, the lines honk refused, and why the groups chosen in
 * 加入群組 could not take them ([failed]; the nodes are in either way).
 */
@Immutable
internal data class RvAddedNodes(val count: Int, val groups: List<String>, val skipped: RvSkipped, val failed: ErrorCode? = null, val unconfirmed: Set<String> = emptySet())

/**
 * A group in 新增節點's 加入群組, by its [name] in honk: one that takes every node already ([every], no node filter and no
 * nested group) shows checked and is never written, as a name filter would shrink it to the new nodes; one no writable
 * configuration file holds cannot be chosen ([writable] false); the others are the user's to [chosen].
 */
@Immutable
internal data class RvGroupPick(val name: String, val every: Boolean, val writable: Boolean, val chosen: Boolean = false) {
    val enabled: Boolean get() = writable && !every
}

/** honk's [groups] in its order, each read from the configuration [sources] that holds its block. */
internal fun groupPicks(groups: List<Group>, sources: List<HonkSource>): List<RvGroupPick> = groups.map { group ->
    val held = sources.firstNotNullOfOrNull { source -> daeGroup(source.content, group.name)?.let { source to it } }
    RvGroupPick(group.name, every = held?.second?.takesEvery == true, writable = held?.first?.writable == true)
}

/** A node honk would not delete: its name for the banner and why. */
@Immutable
internal data class RvNodeDeleteFailed(val id: String, val name: String, val code: ErrorCode)

/**
 * The 組態內 source on 訂閱: its [nodes]; the add sheet while it is open ([sheet]); what the last add did ([added]); the
 * node asked to delete ([deletingId]) and the last delete honk refused ([deleteFailed]).
 */
@Immutable
internal data class RvInlineState(
    val nodes: List<RvNode> = emptyList(),
    val sheet: RvAddNodeSheet? = null,
    val added: RvAddedNodes? = null,
    val deletingId: String? = null,
    val deleteFailed: RvNodeDeleteFailed? = null,
    val loading: Boolean = true,
    val readError: ErrorCode? = null,
)

/** 新增節點's sheet: the field's [text], whether the clipboard may hold a link, what the field says, an add running, and 加入群組. */
@Immutable
internal data class RvAddNodeSheet(
    val text: String = "",
    val clipboard: Boolean = false,
    val check: RvAddNodeCheck = RvAddNodeCheck.None,
    val adding: Boolean = false,
    val groups: List<RvGroupPick> = emptyList(),
    val groupsError: ErrorCode? = null,
)

/** The most lines one add takes, as doona web's batch. */
internal const val ADD_NODE_LINES = 200

/** The longest node name honk takes (`NodeCreate.name`). */
private const val NODE_NAME_MAX = 64

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
