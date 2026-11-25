package dev.doona.android.feature.traffic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.engine.Engine

/** The 流量詳情 entry (`TrafficKey`): builds the ViewModel on [engine] and shows [TrafficPage]. */
@Composable
internal fun TrafficRoute(engine: Engine, onBack: () -> Unit) {
    val viewModel: TrafficViewModel = viewModel(factory = TrafficViewModel.factory(engine))
    TrafficPage(viewModel, onBack)
}

/** 流量詳情 from [viewModel]: its state, collected while the page is started, and the range the page shows. */
@Composable
internal fun TrafficPage(viewModel: TrafficViewModel, onBack: () -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    RvTrafficScreen(state, onRange = viewModel::onRange, onBack = onBack)
}
