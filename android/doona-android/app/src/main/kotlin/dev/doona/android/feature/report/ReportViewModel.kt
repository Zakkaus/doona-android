package dev.doona.android.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import dev.doona.android.feature.errorCode
import dev.doona.android.report.Reports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The 回報問題 page: reads the core's log level once and sets it, and starts the export, whose progress [reports] holds.
 * A level the core refuses goes back to the one before.
 */
class ReportViewModel(private val engine: Engine, private val reports: Reports) : ViewModel() {
    private val level = MutableStateFlow<LogLevel?>(null)
    private val levelError = MutableStateFlow<ErrorCode?>(null)

    val uiState: StateFlow<ReportUiState> = combine(level, reports.export, levelError, ::reportUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), reportUiState(null, reports.export.value, null))

    init {
        // Errors only: the reply carries the ring's level whatever it lists, so the fewest events will do.
        viewModelScope.launch {
            engine.diagnostics(LogLevel.Error).onSuccess { read -> level.compareAndSet(null, read.level) }
        }
    }

    fun onAction(action: ReportAction) {
        when (action) {
            is ReportAction.SetLevel -> setLevel(action.level)

            ReportAction.Export -> reports.startExport()

            ReportAction.DismissError -> {
                levelError.value = null
                reports.dismissFailure()
            }
        }
    }

    private fun setLevel(next: LogLevel) {
        val before = level.value
        level.value = next
        levelError.value = null
        viewModelScope.launch {
            engine.setLogLevel(next).onFailure {
                level.compareAndSet(next, before)
                levelError.value = it.errorCode()
            }
        }
    }

    companion object {
        fun factory(engine: Engine, reports: Reports): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportViewModel(engine, reports) }
        }
    }
}
