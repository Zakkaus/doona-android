package dev.doona.android.feature.permissions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the activity does after a step of [PermissionAsks]: nothing yet, connect, or open one of the system's dialogs. */
enum class PermissionStep { Wait, Connect, AskConsent, AskNotify }

/** Where the notification permission stands: the system's answer, and whether doona asked for it before. */
fun interface NotifyState {
    suspend fun needsAsking(): Boolean
}

/**
 * The way to a connection through Android's two permissions (owner, 2026-11-24 q-perm): each is said first in one or
 * two sentences ([PermissionSheet]), the VPN before the system's consent dialog, the notification once a connection is
 * up. Only a connection that needs the consent is explained, so a later 連線 with consent given connects at once; a
 * cancelled explanation asks nothing and shows again on the next 連線. The notification is asked once ever
 * ([NotifyState]): a refusal, in the explanation or in the system's dialog, does not come back.
 *
 * [showing] is the explanation on screen. [refused] is the last refusal in a system dialog, which 活動 tells
 * (`RvActivityNotice.ConsentDenied`, `NotifyDenied`) until the next connection starts or [clearRefusal]. A pending
 * request ([connect] to the consent's answer) is joined by a repeated tap or a redelivered intent. [showing] and the
 * pending request are saved with the activity, because a pending result is delivered to the recreated one.
 */
class PermissionAsks(private val saved: SavedStateHandle) {
    val showing: StateFlow<Permission?> = saved.getStateFlow(KEY_SHOWING, null)

    private val refusal = MutableStateFlow<Permission?>(null)
    val refused: StateFlow<Permission?> = refusal.asStateFlow()

    private var connecting: Boolean
        get() = saved[KEY_CONNECTING] ?: false
        set(value) {
            saved[KEY_CONNECTING] = value
        }

    /** 連線, from the UI or the app's own connect entry. [consentNeeded]: the system would show its consent dialog. */
    fun connect(consentNeeded: Boolean): PermissionStep {
        if (connecting) return PermissionStep.Wait
        refusal.value = null
        if (!consentNeeded) return PermissionStep.Connect
        connecting = true
        saved[KEY_SHOWING] = Permission.Vpn
        return PermissionStep.Wait
    }

    /** The explanation's 繼續: the system's dialog for the permission explained. */
    fun confirm(): PermissionStep = when (showing.value) {
        Permission.Vpn -> {
            saved[KEY_SHOWING] = null
            PermissionStep.AskConsent
        }

        Permission.Notify -> {
            saved[KEY_SHOWING] = null
            PermissionStep.AskNotify
        }

        null -> PermissionStep.Wait
    }

    /** The explanation's other action, back, or a tap above it: nothing is asked, and a 連線 ends here. */
    fun dismiss() {
        if (showing.value == Permission.Vpn) connecting = false
        saved[KEY_SHOWING] = null
    }

    /** The system's consent dialog answered; given, the activity connects. */
    fun consentAnswered(given: Boolean): PermissionStep {
        connecting = false
        if (given) return PermissionStep.Connect
        refusal.value = Permission.Vpn
        return PermissionStep.Wait
    }

    /** The system's notification dialog answered. */
    fun notifyAnswered(granted: Boolean) {
        if (!granted) refusal.value = Permission.Notify
    }

    /**
     * Each status the activity sees while shown. Once a connection is [running], the notification is explained if it
     * [NotifyState.needsAsking], and recorded as asked by [record] before the explanation shows, so it is asked once.
     */
    suspend fun onStatus(running: Boolean, notify: NotifyState, record: suspend () -> Unit) {
        if (!running || showing.value != null || !notify.needsAsking()) return
        record()
        saved[KEY_SHOWING] = Permission.Notify
    }

    fun clearRefusal() {
        refusal.value = null
    }

    private companion object {
        const val KEY_SHOWING = "permission_showing"
        const val KEY_CONNECTING = "connecting"
    }
}

/** [PermissionAsks] for one activity, kept across its recreation and saved across process death. */
class PermissionViewModel(saved: SavedStateHandle) : ViewModel() {
    val asks = PermissionAsks(saved)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory { initializer { PermissionViewModel(createSavedStateHandle()) } }
    }
}
