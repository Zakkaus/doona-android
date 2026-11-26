package dev.doona.android.prototype.rv.subs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.doona.android.feature.localnetwork.LocalNetworkPrompt
import dev.doona.android.feature.subscriptions.RvAddFailure
import dev.doona.android.feature.subscriptions.RvAddPhase
import dev.doona.android.feature.subscriptions.RvAddState
import dev.doona.android.feature.subscriptions.RvSubDetailState
import dev.doona.android.feature.subscriptions.RvSubsActions
import dev.doona.android.feature.subscriptions.RvSubsPage
import dev.doona.android.feature.subscriptions.rvAgentOf
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.gallery.RV_SAMPLE_LINK
import kotlinx.coroutines.delay

/** The routes of the subscription pages in the preview activity; a route's argument follows its name after a colon. */
internal object RvSubsRoutes {
    const val LIST = "subs"
    const val DETAIL = "sub"
    const val ADD = "sub-add"

    /** 活動 under the deep link's question, as `clash://install-config` would open it. */
    const val IMPORT = "sub-import"
    val all = setOf(LIST, DETAIL, ADD)
}

/**
 * The subscription pages with invented behaviour: 訂閱 (`subs`, or `subs:failed` with Harbor Cloud's failed update), a
 * subscription (`sub:<id>`), and 新增訂閱 (`sub-add`, or `sub-add:<phase>`: `importing`, `done`, `unreachable`,
 * `not-subscription`, `too-large`, `lan-ask` or `lan-denied` with a link on the local network, and `user-agent` or
 * `user-agent-invalid` with a User-Agent typed). An import runs for [IMPORT_MS] and then lists Harbor Cloud's nodes.
 */
@Composable
internal fun RvSubsRoute(route: String, push: (String) -> Unit, pop: () -> Unit) {
    val arg = route.substringAfter(':', "")
    when (route.substringBefore(':')) {
        RvSubsRoutes.LIST -> {
            var state by remember { mutableStateOf(rvSubsStateSample(failed = if (arg == FAILED) rvFetchFailedSample(RvData.subscriptions.first().id) else null)) }
            val actions = RvSubsActions(
                back = pop,
                open = { push("${RvSubsRoutes.DETAIL}:$it") },
                add = { push(RvSubsRoutes.ADD) },
                update = { state = state.copy(failed = null) },
                updateAll = { state = state.copy(failed = null) },
                delete = { state = state.copy(deletingId = it) },
                confirmDelete = { id -> state = state.copy(subscriptions = state.subscriptions.filterNot { it.id == id }, deletingId = null) },
            )
            RvSubsPage(state, actions)
        }

        RvSubsRoutes.DETAIL -> {
            var sub by remember { mutableStateOf(RvData.subscriptions.firstOrNull { it.id == arg } ?: RvData.subscriptions.first()) }
            var state by remember { mutableStateOf(RvSubDetailState()) }
            RvSubDetailSample(sub, state, { state = it }, onBack = pop, onUpdate = {}, onUserAgent = { sub = sub.copy(userAgent = it) })
        }

        else -> {
            var state by remember { mutableStateOf(rvAddSample(arg)) }
            var lan by remember { mutableStateOf(rvAddSampleLan(arg)) }
            LaunchedEffect(state.phase) {
                if (state.phase is RvAddPhase.Importing) {
                    delay(IMPORT_MS)
                    state = state.copy(phase = RvAddPhase.Done(RvData.subscriptions.first().copy(userAgent = rvAgentOf(state.userAgent))))
                }
            }
            RvSubAddSample(
                state,
                { state = it },
                onPaste = { state = state.copy(link = RV_SAMPLE_LINK) },
                onImport = { state = state.copy(phase = RvAddPhase.Importing(IMPORTED_BYTES, IMPORTED_SHARE)) },
                onBack = pop,
                lan = lan,
                // 繼續 stands for Android's dialog answered yes, and the import runs; 開啟設定 leaves the page as it was.
                onLanConfirm = {
                    if (lan == LocalNetworkPrompt.Ask) state = state.copy(phase = RvAddPhase.Importing(IMPORTED_BYTES, IMPORTED_SHARE))
                    lan = null
                },
                onLanDismiss = { lan = null },
            )
        }
    }
}

/** The add page in one of its phases, with the sample link typed for every phase after editing. */
internal fun rvAddSample(phase: String): RvAddState {
    val typed = RvAddState(link = RV_SAMPLE_LINK, name = RvData.subscriptions.first().name)
    return when (phase) {
        "importing" -> typed.copy(phase = RvAddPhase.Importing(IMPORTED_BYTES, IMPORTED_SHARE))
        "importing-unknown" -> typed.copy(phase = RvAddPhase.Importing(null, null))
        "done" -> typed.copy(phase = RvAddPhase.Done(RvData.subscriptions.first()))
        "unreachable" -> typed.copy(phase = RvAddPhase.Failed(RvAddFailure.Unreachable))
        "not-subscription" -> typed.copy(phase = RvAddPhase.Failed(RvAddFailure.NotSubscription))
        "too-large" -> typed.copy(phase = RvAddPhase.Failed(RvAddFailure.TooLarge))
        "lan-ask", "lan-denied" -> typed.copy(link = RV_LAN_LINK, name = "")
        "user-agent" -> typed.copy(userAgent = RV_SAMPLE_AGENT)
        "user-agent-invalid" -> typed.copy(userAgent = RV_SAMPLE_AGENT + RV_SAMPLE_AGENT_BAD)
        else -> RvAddState()
    }
}

/** The local network prompt over the add page in [phase]: the ask or the refusal for a link on the local network. */
internal fun rvAddSampleLan(phase: String): LocalNetworkPrompt? = when (phase) {
    "lan-ask" -> LocalNetworkPrompt.Ask
    "lan-denied" -> LocalNetworkPrompt.Denied
    else -> null
}

private const val FAILED = "failed"

/** A User-Agent as a client sends it, and a tail the core refuses (not printable ASCII). */
internal const val RV_SAMPLE_AGENT = "clash.meta/1.19.0"
internal const val RV_SAMPLE_AGENT_BAD = " 測試"

/** A subscription served on the local network, which needs the local network permission. */
internal const val RV_LAN_LINK = "http://192.168.1.20/sub.yaml"
private const val IMPORT_MS = 1_500L

/** Halfway through Harbor Cloud's body: 1.24 MB of about 3.1 MB. */
private const val IMPORTED_BYTES = 1_240_000L
private const val IMPORTED_SHARE = 0.4f
