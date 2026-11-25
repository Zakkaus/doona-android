package dev.doona.android.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.engine.Engine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What 新增訂閱 typed and follows: the fields, whether the clipboard may hold a link, and the import it waits for. */
internal data class SubAddLocal(
    val link: String = "",
    val name: String = "",
    val userAgent: String = "",
    val clipboardHasLink: Boolean = false,
    val own: Long? = null,
)

/**
 * Where the page's own import stands: none yet, running (the core reports neither a byte count nor a length, so the
 * page shows the unknown progress), failed with its reason, or done with the subscription it added.
 */
internal fun rvAddPhase(own: Long?, import: ImportState): RvAddPhase {
    if (own == null || import.lastId != own) return RvAddPhase.Editing
    if (import.busy) return RvAddPhase.Importing(downloadedBytes = null, fraction = null)
    import.error?.let { return RvAddPhase.Failed(rvAddFailure(it)) }
    return import.added?.let { RvAddPhase.Done(rvSubscription(it)) } ?: RvAddPhase.Editing
}

internal fun subAddState(local: SubAddLocal, import: ImportState) = RvAddState(
    link = local.link,
    name = local.name,
    userAgent = local.userAgent,
    clipboardHasLink = local.clipboardHasLink,
    phase = rvAddPhase(local.own, import),
)

/**
 * 新增訂閱's state holder (plan §2.2). The import is [imports]', which outlives the page: a page opened while one runs,
 * or after one failed while the page was away, follows it and shows what it fetches. The clipboard's content is read
 * only when the user taps its row ([paste]); [refreshClipboard] reads only whether it may hold a link. [protocols]
 * counts what the page's import brought in, by protocol, once it is done.
 */
internal class SubAddViewModel(
    private val engine: Engine,
    private val imports: SubscriptionImports,
    private val clipboard: ClipboardLinks,
) : ViewModel() {
    private val local = MutableStateFlow(followed())
    private val counted = MutableStateFlow<List<RvProtocolCount>>(emptyList())

    val uiState: StateFlow<RvAddState> = combine(local, imports.state, ::subAddState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), subAddState(local.value, imports.state.value))

    val protocols: StateFlow<List<RvProtocolCount>> = counted

    init {
        refreshClipboard()
        viewModelScope.launch {
            // Both flows: an import can end inside start(), before the page has noted it as its own.
            val added = combine(local, imports.state) { page, import -> import.added?.takeIf { page.own != null && import.lastId == page.own && !import.busy } }
                .filterNotNull()
                .first()
            // The catalog takes the nodes in after the call returns; it lists the subscription once it holds them. Every
            // node counts, whether or not a group of the user's names it.
            engine.subscriptions.first { subs -> subs.any { it.id == added.id } }
            val nodes = engine.nodes().getOrNull().orEmpty().filter { it.subscriptionId == added.id }
            counted.value = rvProtocolCounts(nodes.map { it.protocol })
        }
    }

    /** The fields as typed; the link is trimmed, as a pasted link often ends in a space or a newline. */
    fun edit(state: RvAddState) = local.update { it.copy(link = state.link.trim(), name = state.name, userAgent = state.userAgent) }

    /** Whether the clipboard may hold a link, from its description only; the page calls it when it comes to the front. */
    fun refreshClipboard() = local.update { it.copy(clipboardHasLink = clipboard.mayHoldLink()) }

    /** The clipboard's link into the link field, read now that the user asked for it. */
    fun paste() {
        val link = clipboard.link()
        local.update { if (link == null) it.copy(clipboardHasLink = false) else it.copy(link = link) }
    }

    /** Imports what the fields describe (重試 does the same). Nothing starts while an import runs or the fields are not valid. */
    fun import() {
        val typed = local.value
        if (urlProblem(typed.link) != null || !rvNameValid(typed.name) || !rvAgentValid(typed.userAgent)) return
        val id = imports.start(ImportRequest(typed.link, typed.name.trim().ifEmpty { null }, rvAgentOf(typed.userAgent))) ?: return
        local.update { it.copy(own = id) }
    }

    /** A running import, or one that failed while no page showed it, is this page's to follow, with what it was asked to fetch. */
    private fun followed(): SubAddLocal {
        val import = imports.state.value
        val request = import.request
        if (request == null || !(import.busy || import.error != null)) return SubAddLocal()
        return SubAddLocal(request.url, request.name.orEmpty(), request.userAgent.orEmpty(), own = import.lastId)
    }
}
