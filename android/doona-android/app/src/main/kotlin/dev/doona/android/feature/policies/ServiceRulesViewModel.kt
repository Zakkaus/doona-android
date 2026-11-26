package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.vpn.NativeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What 新增服務規則 shows: the main group the new rules send to ([main]), the rule each service has already ([rules], by
 * code), the services ticked ([selected]), and why honk could not be read ([failure]).
 */
@Immutable
internal data class ServiceRulesUiState(
    val main: RvPolicyGroup? = null,
    val rules: Map<String, RvPolicyRule> = emptyMap(),
    val selected: Set<String> = emptySet(),
    val failure: ErrorCode? = null,
)

/** 新增服務規則 on honk's native API: its groups, nodes, providers and rules, read once on [io]; the ticks are the page's. */
internal class ServiceRulesViewModel(
    private val api: NativeApi,
    private val places: NodePlaces = CoreNodePlaces,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val state = MutableStateFlow(ServiceRulesUiState())

    val uiState: StateFlow<ServiceRulesUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val read = withContext(io) { HonkPolicyRead.read(api) }
                val groups = read.policyGroups(read.nodes(places))
                state.update { it.copy(main = read.mainGroupId?.let(groups::get), rules = rvServiceRules(read.policyRules(groups))) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(failure = error.errorCode()) }
            }
        }
    }

    fun toggle(code: String) = state.update { it.copy(selected = if (code in it.selected) it.selected - code else it.selected + code) }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { ServiceRulesViewModel(api) }
        }
    }
}
