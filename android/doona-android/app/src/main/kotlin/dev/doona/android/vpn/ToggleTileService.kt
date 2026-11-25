package dev.doona.android.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.doona.android.R
import dev.doona.android.app.DoonaApplication
import dev.doona.android.app.MainActivity
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.leafOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Shows the current node or VPN status in Quick Settings; a tap connects or disconnects. */
class ToggleTileService : TileService() {
    private val engine: Engine get() = (application as DoonaApplication).container.engine
    private val quickTile: QuickTile get() = (application as DoonaApplication).container.quickTile
    private val scope = MainScope()
    private var listening: Job? = null

    // Called for a tile added through the system dialog or by hand: the settings page offers the tile until it is added.
    override fun onTileAdded() {
        quickTile.record(true)
    }

    override fun onTileRemoved() {
        quickTile.record(false)
    }

    override fun onStartListening() {
        listening = scope.launch {
            val engine = engine
            engine.status.collectLatest { status ->
                if (status is Status.Running) {
                    engine.catalog.map { it?.selectedNodeName() }.distinctUntilChanged().collect { render(status, it) }
                } else {
                    render(status, null)
                }
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        when {
            engine.status.value.active -> engine.disconnect()

            // Consent needs an activity; the app asks for it and then connects.
            VpnService.prepare(this) != null -> openApp()

            else -> connect()
        }
    }

    private fun connect() {
        try {
            engine.connect()
        } catch (e: IllegalStateException) {
            // The system may refuse a foreground service started from here; the app is allowed to start one.
            VpnLog.warn("tile connect refused: ${e.javaClass.simpleName}")
            openApp()
        }
    }

    // Below API 34 the Intent overload is the only one; lint flags it regardless of the version check.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        // The non-exported connect entry: the only way into the app that may start a connection.
        val intent = MainActivity.connectIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun Catalog.selectedNodeName(): String? {
        val group = groups.firstOrNull { it.id == mainGroupId } ?: return null
        val selected = group.members.firstOrNull { it.id == group.selectedId } ?: return null
        return if (selected.isGroup) leafOf(selected, groups.associateBy { it.id })?.name else selected.name
    }

    private fun render(status: Status, nodeName: String?) {
        val tile = qsTile ?: return
        tile.state = if (status.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = nodeName ?: getString(
            when (status) {
                Status.Starting -> R.string.vpn_status_starting
                is Status.Running -> R.string.vpn_status_running
                Status.Stopping -> R.string.vpn_status_stopping
                Status.Stopped -> R.string.vpn_status_stopped
                is Status.Failed -> if (status.reason == FailureReason.Revoked) R.string.vpn_drop_revoked else R.string.vpn_status_failed
            },
        )
        tile.updateTile()
    }
}
