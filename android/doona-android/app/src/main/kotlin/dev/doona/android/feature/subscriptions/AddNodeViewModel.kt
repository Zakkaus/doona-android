package dev.doona.android.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.StaticNodeDraft
import dev.doona.android.feature.activity.activityNodes
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the view model keeps besides the engine's catalog: the sheet, the last add's result, and the delete asked or refused. */
private data class InlineLocal(
    val sheet: RvAddNodeSheet? = null,
    val added: RvAddedNodes? = null,
    val deletingId: String? = null,
    val deleteFailed: RvNodeDeleteFailed? = null,
)

/**
 * 訂閱's 組態內 source (decisions 2026-11-25 23:52 and 2026-11-26 01:03): the nodes added by hand, read from the catalog;
 * 新增節點's sheet, which previews what was pasted through the core ([Engine.previewStaticNodes]) before anything is
 * written, then adds it ([Engine.addStaticNodes]) and says which groups took the new nodes; and 刪除節點, which asks first.
 * The clipboard's content is read only when the user taps its row ([paste]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AddNodeViewModel(private val engine: Engine, private val clipboard: ClipboardLinks) : ViewModel() {
    private val local = MutableStateFlow(InlineLocal())

    /** The 組態內 nodes, each with its endpoint and tests once read; mapped again only when the catalog changes. */
    private val nodes = flow {
        var info = emptyMap<String, NodeInfo>()
        emitAll(
            engine.catalog.mapLatest { catalog ->
                val ids = inlineIds(catalog)
                if (ids.isNotEmpty()) engine.nodes(ids.toList()).getOrNull()?.let { read -> info = read.associateBy { it.id } }
                activityNodes(catalog, info).nodes.filter { it.id in ids }
            },
        )
    }

    val uiState: StateFlow<RvInlineState> = combine(nodes, local) { nodes, local -> RvInlineState(nodes, local.sheet, local.added, local.deletingId, local.deleteFailed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), RvInlineState())

    /** Which sheet is open: each [open] starts a new one, so an answer meant for a sheet since closed finds it gone. */
    private var opened = 0

    /** Opens the sheet empty, asking only whether the clipboard may hold a link. A new add clears the last one's banner. */
    fun open() {
        previewing?.cancel()
        opened++
        local.update { it.copy(sheet = RvAddNodeSheet(clipboard = clipboard.mayHoldText()), added = null, deleteFailed = null) }
    }

    /** Closes the sheet; an add already sent still ends, and only its banner shows. */
    fun close() {
        previewing?.cancel()
        opened++
        local.update { it.copy(sheet = null) }
    }

    /** The field as typed, then what the core says about it; left as it is while an add runs. */
    fun edit(text: String) {
        if (local.value.sheet?.adding != false) return
        local.update { it.copy(sheet = it.sheet?.copy(text = text, check = RvAddNodeCheck.None)) }
        preview(text)
    }

    /** The clipboard's text into the field, read now that the user asked for it; a clip with no link hides the row. */
    fun paste() {
        val text = clipboard.text()?.takeIf { addNodeLines(it).any(AddNodeLine::linkLike) }
        if (text == null) return local.update { it.copy(sheet = it.sheet?.copy(clipboard = false)) }
        local.update { it.copy(sheet = it.sheet?.copy(text = text, clipboard = false)) }
        preview(text)
    }

    /** Adds what the field holds. With nothing added the sheet stays open and says why; otherwise it closes on the result. */
    fun add() {
        val sheet = local.value.sheet?.takeIf { it.check.addable && !it.adding } ?: return
        val lines = addNodeLines(sheet.text)
        val mine = opened

        // The answer goes to the sheet that sent it; a sheet opened since is another add's.
        fun sheetUpdate(change: (RvAddNodeSheet) -> RvAddNodeSheet?) = local.update { if (opened == mine) it.copy(sheet = it.sheet?.let(change)) else it }
        sheetUpdate { it.copy(adding = true) }
        viewModelScope.launch {
            val results = engine.addStaticNodes(drafts(lines)).getOrElse { error ->
                return@launch sheetUpdate { it.copy(adding = false, check = RvAddNodeCheck.Failed(error.errorCode())) }
            }
            val unread = addNodeUnread(sheet.text)
            val (taken, skipped) = sortRows(lines, results)
            if (taken.isEmpty()) return@launch sheetUpdate { it.copy(adding = false, check = RvAddNodeCheck.Refused(skipped.copy(unread = unread))) }
            // The lines the sheet already said it would skip are no news: only rows the preview took and the add refused count,
            // and the lines past the cap, which were never sent: once the sheet closes nothing else says so.
            val known = (sheet.check as? RvAddNodeCheck.Many)?.skipped ?: RvSkipped()
            val refused = RvSkipped(skipped.notLink - known.notLink.toSet(), skipped.duplicate - known.duplicate.toSet(), skipped.other - known.other.toSet(), unread)
            val ids = taken.mapTo(HashSet()) { it.id }
            val catalog = withTimeoutOrNull(CATALOG_MS) { engine.catalog.first { holds(it, ids) } } ?: engine.catalog.value
            val groups = catalog?.let { joinedGroups(it, ids) }.orEmpty()
            // The nodes are in either way: the banner says so, but only this add's own sheet closes.
            local.update { it.copy(sheet = if (opened == mine) null else it.sheet, added = RvAddedNodes(taken.size, groups, refused)) }
        }
    }

    /** Asks to delete node [id], or closes the question when null. */
    fun delete(id: String?) = local.update { it.copy(deletingId = id) }

    /** Deletes node [id] after the question (重試 on the banner, again). A refusal leaves it in the list, with the banner and why. */
    fun confirmDelete(id: String) {
        val name = uiState.value.nodes.firstOrNull { it.id == id }?.name ?: local.value.deleteFailed?.name.orEmpty()
        local.update { it.copy(deletingId = null, deleteFailed = null, added = null) }
        viewModelScope.launch {
            val removed = engine.readStaticNode(id).mapCatching { engine.removeStaticNode(it, confirmed = true).getOrThrow() }
            removed.exceptionOrNull()?.let { error -> local.update { it.copy(deleteFailed = RvNodeDeleteFailed(id, name, error.errorCode())) } }
        }
    }

    /** Clears the last add's banner, when the user starts something else on the page. */
    fun dismissAdded() = local.update { it.copy(added = null) }

    private var previewing: Job? = null

    /** What the core says of [text]'s links, without writing; a later edit replaces an answer still on its way. */
    private fun preview(text: String) {
        previewing?.cancel()
        val lines = addNodeLines(text)
        val unread = addNodeUnread(text)
        val links = lines.filter(AddNodeLine::linkLike)
        previewing = viewModelScope.launch {
            val check = if (links.isEmpty()) {
                addNodeCheck(lines, emptyList(), unread)
            } else {
                engine.previewStaticNodes(drafts(lines)).fold({ addNodeCheck(lines, it, unread) }, { RvAddNodeCheck.Failed(it.errorCode()) })
            }
            local.update { state -> state.copy(sheet = state.sheet?.takeIf { it.text == text }?.copy(check = check) ?: state.sheet) }
        }
    }

    private fun drafts(lines: List<AddNodeLine>): List<StaticNodeDraft> = lines.filter(AddNodeLine::linkLike).map { StaticNodeDraft.Link(it.link) }

    private fun holds(catalog: Catalog?, ids: Set<String>): Boolean {
        val members = catalog?.groups.orEmpty().flatMapTo(HashSet()) { group -> group.members.map { it.id } }
        return members.containsAll(ids)
    }
}

/** How long an add waits for the catalog to list its nodes before it names the groups it has. */
private const val CATALOG_MS = 5_000L
