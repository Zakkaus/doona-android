package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.LogLevel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The core's log level as 日誌等級 reads and sets it: the level now, and a set that answers once the core took it. The
 * app's is its process-wide controller, which is not saved (owner, 2026-11-23 17:43) and returns from 除錯 to 資訊
 * after an hour by itself (owner, 2026-11-24 15:19).
 */
internal interface LogLevels {
    val level: StateFlow<LogLevel>

    suspend fun set(level: LogLevel): Result<Unit>
}

/**
 * 日誌等級: the level the core logs at, as the page's [RvLogLevel]. A choice goes to the core; the page shows what the
 * core took, so a set that fails leaves the level as it was, and 除錯's return to 資訊 shows as it happens.
 */
internal class LogLevelViewModel(private val levels: LogLevels) : ViewModel() {
    val uiState: StateFlow<RvLogLevel> = levels.level.map(::rvLogLevel)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), rvLogLevel(levels.level.value))

    fun select(level: RvLogLevel) {
        viewModelScope.launch { levels.set(level.engineLevel()) }
    }

    companion object {
        fun factory(levels: LogLevels): ViewModelProvider.Factory = viewModelFactory {
            initializer { LogLevelViewModel(levels) }
        }
    }
}
