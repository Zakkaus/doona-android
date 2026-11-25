package dev.doona.android.prototype.rv.settings

import dev.doona.android.feature.model.RvSettingsState
import dev.doona.android.vpn.QuickTileResult

/**
 * The page after the system answered 加到快速設定 with [result]: an added tile hides the row, a failed request opens the
 * sheet that tells how to add it by hand (its words are already there, a banner would need new ones), and a dismissed
 * dialog leaves the page as it was.
 */
internal fun RvSettingsState.afterTileRequest(result: QuickTileResult): RvSettingsState = when (result) {
    QuickTileResult.Added -> copy(tileAdded = true)
    QuickTileResult.Kept -> this
    QuickTileResult.Failed -> copy(tileSheet = true)
}
