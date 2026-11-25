package dev.doona.android.prototype.rv.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.doona.android.app.DoonaApplication
import kotlinx.coroutines.flow.MutableStateFlow

/** Whether 設定 still offers the quick settings tile, and what its 加到快速設定 row does; [added] is null until the store has answered. */
internal class RvQuickTileOffer(val added: Boolean?, val add: () -> Unit)

/**
 * 設定 with the app's real quick settings tile ([DoonaApplication]'s `QuickTile`): the row shows until the tile is
 * recorded as added, and 加到快速設定 asks the system on Android 13 and later, [settings] then following its answer
 * ([afterTileRequest]); on Android 12, which has no such request, it opens the sheet that tells how to add the tile by
 * hand. Outside the app (a preview) the state's own [RvSettingsState.tileAdded] stands and the row opens the sheet.
 */
@Composable
internal fun rvQuickTile(settings: RvSettingsState, onSettings: (RvSettingsState) -> Unit): RvQuickTileOffer {
    val context = LocalContext.current
    val tile = remember(context) { (context.applicationContext as? DoonaApplication)?.container?.quickTile }
    // The container started this state with the app: its current value is the answer unless the app has only just started.
    val flow = remember(tile) { tile?.state ?: MutableStateFlow<Boolean?>(null) }
    val stored by flow.collectAsState()
    val latest by rememberUpdatedState(settings)
    val change by rememberUpdatedState(onSettings)
    return RvQuickTileOffer(if (tile == null) settings.tileAdded else stored) {
        if (tile != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tile.request(context) { change(latest.afterTileRequest(it)) }
        } else {
            change(latest.copy(tileSheet = true))
        }
    }
}
