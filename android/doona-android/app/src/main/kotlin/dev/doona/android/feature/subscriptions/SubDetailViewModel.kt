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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A subscription's page as it is drawn: the subscription (null once it is gone, removed here or elsewhere, and the page
 * leaves), the sheet open on it, and the auto-update interval in hours, null while auto-update is off.
 */
@Immutable
internal data class SubDetailUiState(val sub: RvSubscription?, val sheet: RvSubDetailState = RvSubDetailState(), val updateHours: Int?)

internal fun subDetailUiState(id: String, subscriptions: List<Subscription>, sheet: RvSubDetailState, updateHours: Int?) =
    SubDetailUiState(subscriptions.firstOrNull { it.id == id }?.let(::rvSubscription), sheet, updateHours)

/**
 * Subscription [id]'s page state holder (plan §2.2): the subscription as the engine has it, the auto-update interval
 * in hours as the app's settings give it ([updateHours], null while auto-update is off), and the calls behind 立即更新, 重新命名, the User-Agent sheet and 刪除訂閱. A call that fails leaves the
 * subscription as the engine has it; an update's failure shows on 訂閱, from the core's report.
 */
internal class SubDetailViewModel(
    private val engine: Engine,
    updateHours: Flow<Int?>,
    private val id: String,
) : ViewModel() {
    private val sheet = MutableStateFlow(RvSubDetailState())

    val uiState: StateFlow<SubDetailUiState> =
        combine(engine.subscriptions, sheet, updateHours) { subscriptions, sheet, hours ->
            subDetailUiState(id, subscriptions, sheet, hours)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), subDetailUiState(id, engine.subscriptions.value, sheet.value, DEFAULT_UPDATE_HOURS))

    fun change(state: RvSubDetailState) = sheet.update { state }

    fun update() = call { engine.updateSubscription(id) }

    fun rename(name: String) = call { engine.renameSubscription(id, name) }

    fun setUserAgent(userAgent: String?) = call { engine.setSubscriptionUserAgent(id, userAgent) }

    fun delete() = call { engine.removeSubscription(id) }

    private fun call(action: suspend () -> Result<*>) {
        viewModelScope.launch { action() }
    }
}

/** The interval until the settings are read: the settings' default (`AppSettings`, 12 hours). */
private const val DEFAULT_UPDATE_HOURS = 12
