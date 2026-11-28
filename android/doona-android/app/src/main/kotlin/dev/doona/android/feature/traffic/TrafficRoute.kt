package dev.doona.android.feature.traffic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.app.nativeApi
import dev.doona.android.engine.Status
import kotlinx.coroutines.flow.Flow

/** The 流量詳情 entry (`TrafficKey`): the ViewModel on honk's native API, its state collected while the page is started. */
@Composable
internal fun TrafficRoute(status: Flow<Status>, onBack: () -> Unit) {
    val viewModel: TrafficViewModel = viewModel(factory = TrafficViewModel.factory(nativeApi(), status))
    val state by viewModel.ui.collectAsStateWithLifecycle()
    RvTrafficScreen(state, onRange = viewModel::onRange, onBack = onBack)
}
