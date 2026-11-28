package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkGroupConfig
import dev.doona.android.vpn.honk.HonkSource
import dev.doona.android.vpn.honk.MemberKind
import dev.doona.android.vpn.honk.NativeConfig
import dev.doona.android.vpn.honk.NativeGroupConfig
import dev.doona.android.vpn.honk.daeCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A member of the group being edited: honk's id and name, a node's flag, or a nested group. */
@Immutable
internal data class RvGroupMember(val id: String, val name: String, val group: Boolean, val flag: String? = null)

/**
 * What the group editor shows: the group's [name] and [members] as honk lists them, the file that writes them
 * ([source]), and the policy, `default` and `final` as read ([read]) and as changed ([draft]); null until read. Why a
 * write or the read failed; [done] once honk took the change.
 */
@Immutable
internal data class GroupEditUiState(
    val name: String = "",
    val members: List<RvGroupMember> = emptyList(),
    val source: String? = null,
    val read: HonkGroupConfig? = null,
    val draft: HonkGroupConfig? = null,
    val writeFailure: ErrorCode? = null,
    val readFailure: ErrorCode? = null,
    val done: Boolean = false,
)

/**
 * The group editor (the group page's 編輯) on honk's native API: it reads the group and `GET /groups/{id}/config` on
 * [io], and 套用 sends what changed of the policy, `default` and `final` as honk's PATCH. The name and the members are
 * the source's filters, which that API does not write, so the page opens their file instead (owner,
 * q-group-edit-scope 2026-11-26).
 */
internal class GroupEditViewModel(
    private val api: NativeApi,
    private val groupId: String,
    private val places: NodePlaces = CoreNodePlaces,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val state = MutableStateFlow(GroupEditUiState())
    val uiState: StateFlow<GroupEditUiState> = state.asStateFlow()

    init {
        retry()
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    fun change(draft: HonkGroupConfig) = state.update { it.copy(draft = draft) }

    fun apply() {
        val read = state.value.read ?: return
        val draft = state.value.draft ?: return
        state.update { it.copy(writeFailure = null) }
        viewModelScope.launch {
            try {
                withContext(io) { NativeGroupConfig(api).write(groupId, read, draft) }
                state.update { it.copy(done = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(writeFailure = error.errorCode()) }
            }
        }
    }

    private suspend fun load() {
        try {
            val next = withContext(io) {
                val read = HonkPolicyRead.read(api)
                val group = read.groups.firstOrNull { it.id == groupId } ?: throw EngineException(ErrorCode.NotFound)
                val nodes = read.nodes(places)
                val config = NativeGroupConfig(api).read(groupId)
                GroupEditUiState(
                    name = group.name,
                    members = group.members.map { RvGroupMember(it.id, it.name, it.kind == MemberKind.Group, nodes[it.id]?.place?.flag) },
                    source = groupSource(NativeConfig(api).config(), group.name),
                    read = config,
                    draft = config,
                )
            }
            state.value = next
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.update { it.copy(readFailure = error.errorCode()) }
        }
    }

    companion object {
        fun factory(api: NativeApi, groupId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupEditViewModel(api, groupId) }
        }
    }
}

/** The path of the file whose top-level `group { }` block writes the group [name], else the entry file. */
internal fun groupSource(config: HonkConfig, name: String): String = (groupBlock(config, name)?.first ?: config.main).path

/**
 * Where the group [name] is written: its file and the 0-based lines of its block `name { … }` inside a top-level
 * `group { }`, or null when no file writes it.
 */
internal fun groupBlock(config: HonkConfig, name: String): Pair<HonkSource, IntRange>? {
    val opens = Regex("""(['"]?)${Regex.escape(name)}\1\s*\{.*""")
    config.files.forEach { source ->
        var depth = 0
        var inGroups = false
        var start = -1
        source.content.lines().forEachIndexed { i, line ->
            val code = daeCode(line)
            if (inGroups && depth == 1 && start < 0 && opens.matches(code)) start = i
            if (depth == 0 && code.matches(Regex("""group\s*\{"""))) inGroups = true
            depth += code.count { it == '{' } - code.count { it == '}' }
            if (start >= 0 && depth <= 1) return source to (start..i)
            if (depth <= 0) {
                depth = 0
                inGroups = false
            }
        }
    }
    return null
}
