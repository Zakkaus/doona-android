package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 更新間隔: the stored interval in hours ([selected]), and the 自訂 sheet while [customOpen], with the [customText]
 * typed in it and whether a save found it no count in range ([customInvalid]).
 */
internal data class IntervalUiState(
    val selected: Int,
    val customOpen: Boolean = false,
    val customText: String = "",
    val customInvalid: Boolean = false,
)

/**
 * 更新間隔: how often the subscriptions update, in hours, as the store keeps it (null until it answers), out of its
 * [choices] or a count typed in 自訂 within the store's [range]; a choice is stored at once.
 */
internal class IntervalViewModel(private val store: SettingsStore) : ViewModel() {
    val choices: List<Int> = store.updateIntervals

    val range: IntRange = store.updateHoursRange

    private val sheet = MutableStateFlow(Sheet())

    val uiState: StateFlow<IntervalUiState?> =
        combine(store.updateHours, sheet) { hours, sheet -> IntervalUiState(hours, sheet.open, sheet.text, sheet.invalid) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(hours: Int) {
        viewModelScope.launch { store.setUpdateHours(hours) }
    }

    /** Opens 自訂 holding the stored count when it is no preset, else empty. */
    fun openCustom() {
        val typed = uiState.value?.selected?.takeIf { it !in choices }?.toString().orEmpty()
        sheet.value = Sheet(open = true, text = typed)
    }

    fun editCustom(text: String) = sheet.update { it.copy(text = text, invalid = false) }

    fun dismissCustom() = sheet.update { it.copy(open = false) }

    /** Stores the typed count and closes the sheet; a count that is no whole number in [range] keeps it open with why. */
    fun saveCustom() {
        val hours = sheet.value.text.trim().toIntOrNull()?.takeIf { it in range }
        if (hours == null) {
            sheet.update { it.copy(invalid = true) }
            return
        }
        sheet.update { it.copy(open = false) }
        select(hours)
    }

    private data class Sheet(val open: Boolean = false, val text: String = "", val invalid: Boolean = false)

    companion object {
        fun factory(store: SettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { IntervalViewModel(store) }
        }
    }
}
