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
 * ([NotifyState]): a refusal, in the explanation or in the system's dialog, does not come back. It counts as asked once
 * the user answers, in the explanation or in the system's dialog; [unrecorded] holds that answer until [onStatus] has
 * stored it, so neither an activity that dies before the explanation shows nor one that dies before the store loses it.
 *
 * [showing] is the explanation on screen. [refused] is the last refusal in a system dialog, which 活動 tells
 * (`RvActivityNotice.ConsentDenied`, `NotifyDenied`) until the next connection starts or [clearRefusal]. A pending
 * request ([connect] to the consent's answer) is joined by a repeated tap or a redelivered intent. [showing] and the
 * pending request are saved with the activity, because a pending result is delivered to the recreated one, and so are
 * the notification's open dialog and its unrecorded answer.
 */
class PermissionAsks(private val saved: SavedStateHandle) {
    val showing: StateFlow<Permission?> = saved.getStateFlow(KEY_SHOWING, null)

    private val refusal = MutableStateFlow<Permission?>(null)
    val refused: StateFlow<Permission?> = refusal.asStateFlow()

    /** The notification was answered and the answer is not stored yet; [onStatus] stores it, and retries until it can. */
    val unrecorded: StateFlow<Boolean> = saved.getStateFlow(KEY_UNRECORDED, false)

    private var connecting: Boolean
        get() = saved[KEY_CONNECTING] ?: false
        set(value) {
            saved[KEY_CONNECTING] = value
        }

    /** The system's notification dialog is open: its answer comes back to this or a recreated activity. */
    private var notifyOpen: Boolean
        get() = saved[KEY_NOTIFY_OPEN] ?: false
        set(value) {
            saved[KEY_NOTIFY_OPEN] = value
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
            notifyOpen = true
            saved[KEY_SHOWING] = null
            PermissionStep.AskNotify
        }

        null -> PermissionStep.Wait
    }

    /** The explanation's other action, back, or a tap above it: nothing is asked, and a 連線 ends here. */
    fun dismiss() {
        when (showing.value) {
            Permission.Vpn -> connecting = false
            Permission.Notify -> saved[KEY_UNRECORDED] = true
            null -> Unit
        }
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
        notifyOpen = false
        saved[KEY_UNRECORDED] = true
        if (!granted) refusal.value = Permission.Notify
    }

    /**
     * Each status the activity sees while shown, and each new answer. An answer not yet stored is recorded as asked by
     * [record] first; if that throws, it stays [unrecorded] and the next call tries again. Once a connection is
     * [running], the notification is explained if it [NotifyState.needsAsking] and is not already on its way.
     */
    suspend fun onStatus(running: Boolean, notify: NotifyState, record: suspend () -> Unit) {
        if (unrecorded.value) {
            record()
            saved[KEY_UNRECORDED] = false
        }
        if (!running || !free() || !notify.needsAsking() || !free()) return
        saved[KEY_SHOWING] = Permission.Notify
    }

    /** Nothing is explained and the notification is neither asked nor waiting to be stored. */
    private fun free() = showing.value == null && !notifyOpen && !unrecorded.value

    fun clearRefusal() {
        refusal.value = null
    }

    private companion object {
        const val KEY_SHOWING = "permission_showing"
        const val KEY_CONNECTING = "connecting"
        const val KEY_NOTIFY_OPEN = "notify_open"
        const val KEY_UNRECORDED = "notify_unrecorded"
    }
}

/** [PermissionAsks] for one activity, kept across its recreation and saved across process death. */
class PermissionViewModel(saved: SavedStateHandle) : ViewModel() {
    val asks = PermissionAsks(saved)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory { initializer { PermissionViewModel(createSavedStateHandle()) } }
    }
}
