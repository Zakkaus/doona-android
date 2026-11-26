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
import kotlinx.coroutines.launch

/**
 * The core's log level as 日誌等級 reads and sets it: the level now, and a set that answers once the core took it. The
 * app's is its process-wide controller, which is not saved (owner, 2026-11-23 17:43) and returns from 除錯 to 資訊
 * after an hour by itself (owner, 2026-11-24 15:19).
 */
internal interface LogLevels {
    val level: StateFlow<RvLogLevel>

    suspend fun set(level: RvLogLevel): Result<Unit>
}

/** What 日誌等級 shows: the core's level, and the choice it [refused] last, until a later choice goes through. */
internal data class LogLevelUiState(val level: RvLogLevel, val refused: RvLogLevel? = null)

/**
 * 日誌等級: the level the core logs at, as the page's [RvLogLevel]. A choice goes to the core; the page shows what the
 * core took, so a set that fails leaves the level as it was and says so ([LogLevelUiState.refused], which [retry] asks
 * again), and 除錯's return to 資訊 shows as it happens.
 */
internal class LogLevelViewModel(private val levels: LogLevels) : ViewModel() {
    private val refused = MutableStateFlow<RvLogLevel?>(null)

    val uiState: StateFlow<LogLevelUiState> = combine(levels.level, refused) { level, refused -> LogLevelUiState(level, refused) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogLevelUiState(levels.level.value))

    fun select(level: RvLogLevel) {
        viewModelScope.launch { refused.value = if (levels.set(level).isFailure) level else null }
    }

    /** Asks the core again for the level it refused. */
    fun retry() {
        refused.value?.let(::select)
    }

    companion object {
        fun factory(levels: LogLevels): ViewModelProvider.Factory = viewModelFactory {
            initializer { LogLevelViewModel(levels) }
        }
    }
}
