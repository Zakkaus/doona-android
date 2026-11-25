package dev.doona.android.feature.activity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

/**
 * What the user refused on the way to connecting, which 活動 shows as a notice: the VPN consent ([vpn]) until a later
 * consent is given, and the notification permission ([notifications]) until it is allowed. The activity that asks for
 * both sets them; they last as long as the process.
 */
class ConnectRefusals {
    val vpn = MutableStateFlow(false)
    val notifications = MutableStateFlow(false)
}

/** The notices' inputs from outside the engine: whether a network is up, and what the user refused. */
internal data class ActivitySignals(val online: Boolean = true, val vpnRefused: Boolean = false, val notifyRefused: Boolean = false)

/**
 * Whether a network is up, as 活動 shows it: up at first, and down only once [online] has stayed false for [graceMs].
 * The network source reads offline until Android first answers, and a handover drops the default network for a moment;
 * neither is worth a notice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun steadyOnline(online: Flow<Boolean>, graceMs: Long = OFFLINE_GRACE_MS): Flow<Boolean> =
    online.distinctUntilChanged().transformLatest { up ->
        if (!up) delay(graceMs)
        emit(up)
    }.onStart { emit(true) }.distinctUntilChanged()

/** How long the network must stay down before 活動 says so. */
internal const val OFFLINE_GRACE_MS = 2_000L
