package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Subscription
import dev.doona.android.feature.model.RvSubscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A subscription's page as it is drawn: the subscription, the sheet open on it, and the auto-update interval. Once the
 * engine is ready and has no such subscription (removed here or elsewhere, or before a back stack restored after process
 * death) [gone] is set and the page leaves; [sub] then stays the last one seen, so the page is still drawn while it
 * leaves. Before the engine is ready the page draws nothing and stays.
 */
@Immutable
internal data class SubDetailUiState(
    val sub: RvSubscription?,
    val sheet: RvSubDetailState = RvSubDetailState(),
    val autoUpdate: RvAutoUpdate,
    val gone: Boolean = false,
)

internal fun subDetailUiState(id: String, ready: Boolean, subscriptions: List<Subscription>, sheet: RvSubDetailState, autoUpdate: RvAutoUpdate): SubDetailUiState {
    val sub = subscriptions.firstOrNull { it.id == id }?.let(::rvSubscription)
    return SubDetailUiState(sub, sheet, autoUpdate, gone = ready && sub == null)
}

/**
 * Subscription [id]'s page state holder (plan §2.2): the subscription as the engine has it, the auto-update interval
 * in hours as the app's settings give it ([updateHours], null while auto-update is off; none shown until its first
 * value), and the calls behind 立即更新, 重新命名, the User-Agent sheet and 刪除訂閱. A call that fails leaves the
 * subscription as the engine has it; an update's failure shows on 訂閱, from the core's report.
 */
internal class SubDetailViewModel(
    private val engine: Engine,
    updateHours: Flow<Int?>,
    private val id: String,
) : ViewModel() {
    private val sheet = MutableStateFlow(RvSubDetailState())

    val uiState: StateFlow<SubDetailUiState> =
        combine(engine.ready, engine.subscriptions, sheet, updateHours.map(::rvAutoUpdate).onStart { emit(RvAutoUpdate.Unread) }) { ready, subscriptions, sheet, autoUpdate ->
            subDetailUiState(id, ready, subscriptions, sheet, autoUpdate)
        }.runningReduce { last, next -> if (next.gone) next.copy(sub = last.sub) else next }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_MS),
                subDetailUiState(id, engine.ready.value, engine.subscriptions.value, sheet.value, RvAutoUpdate.Unread),
            )

    fun change(state: RvSubDetailState) = sheet.update { state }

    fun update() = call { engine.updateSubscription(id) }

    /** Renames through the core; a refusal opens the sheet again on [name] with the failure under it. */
    fun rename(name: String) {
        viewModelScope.launch {
            engine.renameSubscription(id, name).onFailure { sheet.update { RvSubDetailState(RvSubSheet.Rename, name, renameFailed = true) } }
        }
    }

    fun setUserAgent(userAgent: String?) = call { engine.setSubscriptionUserAgent(id, userAgent) }

    /** Deletes through the core (重試 on the banner, again); a refusal shows the banner over the page. */
    fun delete() {
        sheet.update { it.copy(deleteFailed = false) }
        viewModelScope.launch {
            if (engine.removeSubscription(id).isFailure) sheet.update { it.copy(deleteFailed = true) }
        }
    }

    private fun call(action: suspend () -> Result<*>) {
        viewModelScope.launch { action() }
    }
}
