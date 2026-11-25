package dev.doona.android.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.doona.android.feature.localnetwork.LocalNetworkHost
import dev.doona.android.feature.localnetwork.LocalNetworkPrompts
import dev.doona.android.feature.permissions.PermissionAsks
import dev.doona.android.feature.permissions.PermissionPrompts
import dev.doona.android.feature.permissions.PermissionStep

/** The sheet [AppSheets] has up. */
private enum class AppSheet { Permission, LocalNetwork }

/**
 * The sheets about Android's permissions over every page, from this one host: the permission explanations of [asks]
 * ([PermissionPrompts], 繼續 handing its step to [onStep]) and [localNetwork]'s prompts ([LocalNetworkPrompts]). One
 * shows at a time, so an answer is never hidden under the other sheet: the one up stays until it closes while the other
 * waits; when both wait, the permission goes first, since it carries the user's own 連線.
 */
@Composable
fun AppSheets(asks: PermissionAsks, onStep: (PermissionStep) -> Unit, localNetwork: LocalNetworkHost?) {
    val permission by asks.showing.collectAsStateWithLifecycle()
    val lan = localNetwork?.let { it.access.prompt.collectAsStateWithLifecycle().value }
    // A plain holder: it changes only with the two states read above, each of which recomposes this.
    val up = remember { arrayOf<AppSheet?>(null) }
    up[0] = when {
        up[0] == AppSheet.LocalNetwork && lan != null -> AppSheet.LocalNetwork
        permission != null -> AppSheet.Permission
        lan != null -> AppSheet.LocalNetwork
        else -> null
    }
    PermissionPrompts(asks, visible = up[0] == AppSheet.Permission, onStep = onStep)
    localNetwork?.let { LocalNetworkPrompts(it, visible = up[0] == AppSheet.LocalNetwork) }
}
