package dev.doona.android.feature.permissions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

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
 * [showing] is the explanation on screen. One that is wanted while another shows, or while a system dialog is open
 * (the consent's, or the notification's from [notifyLaunched] to [notifyAnswered]), waits, and [advance], called when
 * a sheet closes and by every answer and status, is the only place that shows a waiting one: the VPN's when nothing
 * else is open, the notification's once a connection is [running] and no consent is pending. So a 連線 from the tile
 * does not take the notification's explanation away, and neither explanation meets the other's system dialog; a
 * consent that is refused leaves the notification unasked. [refused] is the last refusal, the explanation's 取消 or
 * 暫時不要 or a no in the system's dialog, which 活動 tells (`RvActivityNotice.ConsentDenied`, `NotifyDenied`) until
 * the next connection starts or [clearRefusal]; back or a tap above the explanation ([close]) refuses nothing. A
 * pending request ([connect] to the consent's answer) is joined by a repeated tap or a redelivered intent. Everything
 * but [running] is saved with the activity, because a pending result is delivered to the recreated one.
 */
class PermissionAsks(private val saved: SavedStateHandle) {
    val showing: StateFlow<Permission?> = saved.getStateFlow(KEY_SHOWING, null)

    val refused: StateFlow<Permission?> = saved.getStateFlow(KEY_REFUSED, null)

    /** The notification was answered and the answer is not stored yet; [onStatus] stores it, and retries until it can. */
    val unrecorded: StateFlow<Boolean> = saved.getStateFlow(KEY_UNRECORDED, false)

    /** The last status [onStatus] saw; a new process learns it again from the first status. */
    private var running = false

    private var connecting by flag(KEY_CONNECTING)

    /** A VPN explanation is wanted and waits for [advance]. */
    private var vpnWaits by flag(KEY_VPN_WAITS)

    /** The notification explanation is wanted and waits for [advance]. */
    private var notifyWaits by flag(KEY_NOTIFY_WAITS)

    /** The system's consent dialog is open: from [confirm] to [consentAnswered]. */
    private var consentOpen by flag(KEY_CONSENT_OPEN)

    /** The system's notification dialog is open: its answer comes back to this or a recreated activity. */
    private var notifyOpen by flag(KEY_NOTIFY_OPEN)

    private fun flag(key: String) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = saved[key] ?: false

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            saved[key] = value
        }
    }

    /** 連線, from the UI or the app's own connect entry. [consentNeeded]: the system would show its consent dialog. */
    fun connect(consentNeeded: Boolean): PermissionStep {
        if (connecting) return PermissionStep.Wait
        clearRefusal()
        if (!consentNeeded) return PermissionStep.Connect
        connecting = true
        vpnWaits = true
        advance()
        return PermissionStep.Wait
    }

    /**
     * The explanation's 繼續: the system's dialog for the permission explained. The activity opens it at once, so nothing
     * is promoted here; [notifyLaunched] marks the notification's dialog open, or [notifyLaunchFailed] gives up on it.
     */
    fun confirm(): PermissionStep = when (showing.value) {
        Permission.Vpn -> {
            saved[KEY_SHOWING] = null
            consentOpen = true
            PermissionStep.AskConsent
        }

        Permission.Notify -> {
            saved[KEY_SHOWING] = null
            PermissionStep.AskNotify
        }

        null -> PermissionStep.Wait
    }

    /** The explanation's other action, 取消 or 暫時不要: nothing is asked, a 連線 ends here, and the refusal is told. */
    fun dismiss() {
        val permission = showing.value ?: return
        close()
        saved[KEY_REFUSED] = permission
    }

    /**
     * Back or a tap above the explanation: nothing is asked and a 連線 ends here, as with [dismiss], but nothing is
     * refused. The notification's explanation counts as answered, so it is not asked again.
     */
    fun close() {
        when (showing.value) {
            Permission.Vpn -> connecting = false
            Permission.Notify -> saved[KEY_UNRECORDED] = true
            null -> return
        }
        saved[KEY_SHOWING] = null
        advance()
    }

    /** The system's consent dialog answered; given, the activity connects. */
    fun consentAnswered(given: Boolean): PermissionStep {
        connecting = false
        consentOpen = false
        if (!given) saved[KEY_REFUSED] = Permission.Vpn
        advance()
        return if (given) PermissionStep.Connect else PermissionStep.Wait
    }

    /** The activity launched the system's notification dialog. A launch that throws never gets here ([notifyLaunchFailed]). */
    fun notifyLaunched() {
        notifyOpen = true
    }

    /** The system's notification dialog could not be opened: nothing was asked, the next status explains again. */
    fun notifyLaunchFailed() {
        advance()
    }

    /** The system's notification dialog answered. */
    fun notifyAnswered(granted: Boolean) {
        notifyOpen = false
        saved[KEY_UNRECORDED] = true
        if (!granted) saved[KEY_REFUSED] = Permission.Notify
        advance()
    }

    /**
     * Each status the activity sees while shown, and each new answer. An answer not yet stored is recorded as asked by
     * [record] first; if that throws, it stays [unrecorded] and the next call tries again. Once a connection is
     * [running], the notification is wanted if it [NotifyState.needsAsking] and is not already on its way.
     *
     * A 連線 may land while the flag is read (the tile, on the main thread); the notification then waits behind its
     * explanation. A consent needed while a connection runs does not happen (a tile tap while running disconnects), so
     * there is no case of the notification meeting the consent's dialog here; [advance] holds it back all the same.
     */
    suspend fun onStatus(running: Boolean, notify: NotifyState, record: suspend () -> Unit) {
        this.running = running
        if (unrecorded.value) {
            record()
            saved[KEY_UNRECORDED] = false
        }
        val onItsWay = showing.value == Permission.Notify || notifyWaits || notifyOpen || unrecorded.value
        if (running && !onItsWay && notify.needsAsking()) notifyWaits = true
        advance()
    }

    /**
     * The only place that shows a waiting explanation, and only while no system dialog is open and nothing shows: the
     * VPN's, or the notification's once a connection is up and no consent is pending.
     */
    private fun advance() {
        if (showing.value != null || notifyOpen || consentOpen) return
        when {
            vpnWaits -> {
                vpnWaits = false
                saved[KEY_SHOWING] = Permission.Vpn
            }

            notifyWaits && running && !connecting -> {
                notifyWaits = false
                saved[KEY_SHOWING] = Permission.Notify
            }
        }
    }

    fun clearRefusal() {
        saved[KEY_REFUSED] = null
    }

    private companion object {
        const val KEY_SHOWING = "permission_showing"
        const val KEY_VPN_WAITS = "permission_vpn_waits"
        const val KEY_NOTIFY_WAITS = "permission_notify_waits"
        const val KEY_REFUSED = "permission_refused"
        const val KEY_CONNECTING = "connecting"
        const val KEY_CONSENT_OPEN = "consent_open"
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
