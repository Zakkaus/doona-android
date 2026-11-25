package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 更新間隔: how often the subscriptions update, in hours, as the store keeps it; a choice is stored at once. */
internal class IntervalViewModel(private val store: SettingsStore) : ViewModel() {
    val uiState: StateFlow<Int> = store.updateHours.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_HOURS)

    fun select(hours: Int) {
        viewModelScope.launch { store.setUpdateHours(hours) }
    }

    companion object {
        /** The interval the store reads when nothing is stored, shown until it answers. */
        const val DEFAULT_HOURS = 12

        fun factory(store: SettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { IntervalViewModel(store) }
        }
    }
}
