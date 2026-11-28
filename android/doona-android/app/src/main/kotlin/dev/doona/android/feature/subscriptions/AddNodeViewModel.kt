package dev.doona.android.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.rvNode
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.NativeConfig
import dev.doona.android.honk.Node
import dev.doona.android.honk.daeJoined
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the view model keeps besides honk's nodes: the sheet, the last add's result, and the delete asked or refused. */
private data class InlineLocal(
    val sheet: RvAddNodeSheet? = null,
    val added: RvAddedNodes? = null,
    val deletingId: String? = null,
    val deleteFailed: RvNodeDeleteFailed? = null,
    val readError: ErrorCode? = null,
)

/**
 * 訂閱's 組態內 source (decisions 2026-11-25 23:52 and 2026-11-26 01:03): honk's nodes added by their link, read on [io]
 * at start and after each add or delete, as honk pushes nothing; 新增節點's sheet, which adds each share link it holds
 * with honk's POST /nodes and says which groups took the new nodes; and 刪除節點, which asks first. honk previews no
 * link, so the sheet says only what it can tell before honk reads one: which lines are no share link and which names
 * are in use. Its 加入群組 lists honk's groups; the ones chosen take the new nodes by name in the configuration file that
 * holds each, one replace a file. The clipboard's content is read only when the user taps its row ([paste]).
 */
internal class AddNodeViewModel(
    api: NativeApi,
    private val clipboard: ClipboardLinks,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val places: NodePlaces = CoreNodePlaces,
) : ViewModel() {
    private val catalog = NativeCatalog(api)
    private val config = NativeConfig(api)
    private val catalogNodes = MutableStateFlow<Pair<List<Node>, Set<String>>?>(null)
    private val local = MutableStateFlow(InlineLocal())
    private var reading: Job? = null

    val uiState: StateFlow<RvInlineState> = combine(catalogNodes, local) { catalog, local ->
        RvInlineState(catalog?.first.orEmpty().map { rvNode(it, emptyMap(), places) }, local.sheet, local.added, local.deletingId, local.deleteFailed, catalog == null && local.readError == null, local.readError)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), RvInlineState())

    /** Which sheet is open: each [open] starts a new one, so an answer meant for a sheet since closed finds it gone. */
    private var opened = 0

    /**
     * Opens a fresh form, checks the clipboard without reading it and loads the group choices.
     */
    fun open() {
        opened++
        local.update { it.copy(sheet = RvAddNodeSheet(clipboard = clipboard.mayHoldText()), added = null, deleteFailed = null) }
        loadGroups()
    }

    fun loadGroups() {
        val mine = opened
        viewModelScope.launch {
            val picks = attempt { groupPicks(groups(), config.config().sources) }
            local.update { state ->
                val sheet = state.sheet ?: return@update state
                if (opened != mine) return@update state
                val groups = picks.getOrNull()?.map { group -> group.copy(chosen = group.enabled && sheet.groups.any { it.name == group.name && it.chosen }) } ?: sheet.groups
                state.copy(sheet = sheet.copy(groups = groups, groupsError = picks.exceptionOrNull()?.errorCode()))
            }
        }
    }

    /** Chooses group [name] in 加入群組, or no longer; a group that takes every node or that no file can hold stays as it is. */
    fun pick(name: String) = local.update { state ->
        val sheet = state.sheet?.takeIf { !it.adding } ?: return@update state
        state.copy(sheet = sheet.copy(groups = sheet.groups.map { if (it.name == name && it.enabled) it.copy(chosen = !it.chosen) else it }))
    }

    /** Closes the sheet; an add already sent still ends, and only its banner shows. */
    fun close() {
        opened++
        local.update { it.copy(sheet = null) }
    }

    /** The field as typed, and what it says of it; left as it is while an add runs. */
    fun edit(text: String) {
        if (local.value.sheet?.adding != false) return
        local.update { it.copy(sheet = it.sheet?.copy(text = text, check = check(text))) }
    }

    /** The clipboard's text into the field, read now that the user asked for it; a clip with no link hides the row. */
    fun paste() {
        val text = clipboard.text()?.takeIf { addNodeLines(it).any(AddNodeLine::linkLike) }
        if (text == null) return local.update { it.copy(sheet = it.sheet?.copy(clipboard = false)) }
        local.update { it.copy(sheet = it.sheet?.copy(text = text, clipboard = false, check = check(text))) }
    }

    /**
     * Adds what the field holds, one POST /nodes a line. With nothing added the sheet stays open and says why (honk's
     * error when one stopped it, else the lines it refused); otherwise it closes on the result.
     */
    fun add() {
        val sheet = local.value.sheet?.takeIf { it.check.addable && !it.adding } ?: return
        val (sent, skipped) = addNodeRows(addNodeLines(sheet.text), catalogNodes.value?.second ?: return)
        val unread = addNodeUnread(sheet.text)
        val mine = opened

        // The answer goes to the sheet that sent it; a sheet opened since is another add's.
        fun sheetUpdate(change: (RvAddNodeSheet) -> RvAddNodeSheet?) = local.update { if (opened == mine) it.copy(sheet = it.sheet?.let(change)) else it }
        sheetUpdate { it.copy(adding = true) }
        viewModelScope.launch {
            val added = mutableListOf<Node>()
            val refused = mutableListOf<Int>()
            var stopped: ErrorCode? = null
            sent.forEach { (line, name) ->
                attempt { addNode(name, line.link) }.fold({ added += it }) { error ->
                    // A link honk cannot read, or a name it holds already, is that line's; any other error is honk's.
                    refused += line.line
                    error.errorCode().takeIf { it != ErrorCode.InvalidArgument }?.let { stopped = stopped ?: it }
                }
            }
            if (added.isEmpty()) {
                val check = stopped?.let(RvAddNodeCheck::Failed) ?: RvAddNodeCheck.Refused(skipped.copy(other = refused, unread = unread))
                return@launch sheetUpdate { it.copy(adding = false, check = check) }
            }
            // The sheet said which lines it would skip; only the lines honk refused are news, and the lines past the cap,
            // which were never sent: once the sheet closes nothing else says so.
            val chosen = sheet.groups.filter { it.chosen }.map { it.name }
            val failed = if (chosen.isEmpty()) null else attempt { join(chosen, added) }.exceptionOrNull()?.errorCode()
            val ids = added.mapTo(HashSet()) { it.id }
            val groups = attempt { joinedGroups(groups(), ids) }.getOrNull()
            // The nodes are in either way: the banner says so, but only this add's own sheet closes.
            val result = RvAddedNodes(added.size, groups.orEmpty(), RvSkipped(other = refused, unread = unread), failed, if (groups == null) ids else emptySet())
            local.update { it.copy(sheet = if (opened == mine) null else it.sheet, added = result) }
            load()
        }
    }

    /** Asks to delete node [id], or closes the question when null. */
    fun delete(id: String?) = local.update { it.copy(deletingId = id) }

    /**
     * Deletes node [id] after the question (重試 on the banner, again). A refusal leaves it in the list, with the banner
     * explaining why.
     */
    fun confirmDelete(id: String) {
        val name = catalogNodes.value?.first?.firstOrNull { it.id == id }?.name ?: local.value.deleteFailed?.name.orEmpty()
        local.update { it.copy(deletingId = null, deleteFailed = null, added = null) }
        viewModelScope.launch {
            val code = attempt { removeNode(id) }.exceptionOrNull()?.errorCode()
            if (code != null) local.update { it.copy(deleteFailed = RvNodeDeleteFailed(id, name, code)) }
            load()
        }
    }

    /** Clears the last add's banner, when the user starts something else on the page. */
    fun dismissAdded() = local.update { it.copy(added = null) }

    fun confirmGroups() {
        val result = local.value.added ?: return
        viewModelScope.launch {
            attempt { joinedGroups(groups(), result.unconfirmed) }.onSuccess { groups ->
                local.update { if (it.added === result) it.copy(added = result.copy(groups = groups, unconfirmed = emptySet())) else it }
            }
        }
    }

    /** Reads 組態內's nodes again; a failed read keeps the list last read. */
    fun load() {
        reading?.cancel()
        reading = viewModelScope.launch {
            val read = attempt {
                val all = nodes()
                inlineNodes(all, providers()) to all.mapTo(HashSet()) { it.name }
            }
            read.onSuccess { snapshot ->
                val previous = catalogNodes.value
                catalogNodes.value = snapshot
                if (previous?.second != snapshot.second) local.value.sheet?.let { edit(it.text) }
            }
            local.update { it.copy(readError = read.exceptionOrNull()?.errorCode()) }
        }
    }

    /**
     * Writes [added] into each of the [chosen] groups that does not hold them yet (its filters may match them already),
     * one replace of each configuration file that changes, waiting for honk's reload.
     */
    private suspend fun join(chosen: List<String>, added: List<Node>) {
        val held = catalog.groups().associate { group -> group.name to group.members.mapTo(HashSet()) { it.id } }
        val joins = chosen.associateWith { group -> added.filter { it.id !in held[group].orEmpty() }.map { it.name } }.filterValues { it.isNotEmpty() }
        if (joins.isEmpty()) return
        config.config().sources.filter { it.writable }.forEach { source ->
            val content = daeJoined(source.content, joins)
            if (content != source.content) config.replace(source, content)
        }
    }

    private fun check(text: String) = catalogNodes.value?.second?.let { addNodeCheck(addNodeLines(text), it, addNodeUnread(text)) } ?: RvAddNodeCheck.None

    /** [action] on [io]; its failure, a cancellation apart, is the result's. */
    private suspend fun <T> attempt(action: suspend NativeCatalog.() -> T): Result<T> = try {
        Result.success(withContext(io) { catalog.action() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
