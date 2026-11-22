package dev.doona.android.feature.localnetwork

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the app shows about local network access: the one-line reason before the system's question, or a refusal. */
enum class LocalNetworkPrompt { Ask, Denied }

/**
 * The `ACCESS_LOCAL_NETWORK` permission, asked for only after something on the local network failed (ARCH D26). The
 * question comes at most once per process: a refusal, in doona's reason or in the system's dialog, is final until the
 * process ends, and a later failure only shows [LocalNetworkPrompt.Denied], which leads to the app's settings. Closing
 * the reason with back or a tap outside it is no refusal. The app shows [prompt] and reports what the user did;
 * [granted] reads the permission, true where Android has none.
 */
class LocalNetworkAccess(private val granted: () -> Boolean) {
    private val shown = MutableStateFlow<LocalNetworkPrompt?>(null)
    val prompt: StateFlow<LocalNetworkPrompt?> = shown.asStateFlow()

    private val lock = Any()
    private var denied = false
    private var pending: CompletableDeferred<Boolean>? = null

    /** The callers still waiting for [pending]. */
    private var waiting = 0

    /**
     * Called after a user's action failed on the local network. Returns true when the user has just allowed access, so
     * the action is worth one more try; false when it was already allowed (the failure has another cause), or when the
     * user refuses now or refused earlier in this process. Callers that arrive while a question is open share its answer.
     * When the last of them is cancelled while the reason is still shown, the reason is withdrawn and nothing is refused;
     * once the user went on to the system's question, its answer still counts.
     */
    suspend fun ask(): Boolean {
        if (granted()) return false
        val answer = synchronized(lock) {
            if (denied) {
                shown.value = LocalNetworkPrompt.Denied
                return false
            }
            val open = pending ?: CompletableDeferred<Boolean>().also {
                pending = it
                waiting = 0
                shown.value = LocalNetworkPrompt.Ask
            }
            waiting++
            open
        }
        try {
            return answer.await()
        } finally {
            withdraw(answer)
        }
    }

    /**
     * The running tunnel failed to reach a node on the local network: asks, or shows the refusal again. A refusal shown
     * again here stays only while the caller waits, so it comes down when the tunnel's report clears.
     */
    suspend fun noticed() {
        val refused = synchronized(lock) { denied }
        if (ask() || !refused || granted()) return
        try {
            awaitCancellation()
        } finally {
            dismissed()
        }
    }

    /** The user went on from doona's reason to the system's question; the reason is no longer shown. */
    fun requesting() {
        synchronized(lock) { if (shown.value == LocalNetworkPrompt.Ask) shown.value = null }
    }

    /** The system's answer. A refusal shows why doona cannot reach the local network and how to allow it. */
    fun answered(allowed: Boolean) = settle(allowed, refused = !allowed, next = if (allowed) null else LocalNetworkPrompt.Denied)

    /** The user turned down doona's reason with its button: a refusal, without a second dialog about it. */
    fun declined() = settle(allowed = false, refused = true, next = null)

    /** The user closed doona's reason with back or a tap outside it: no retry now, and the next failure asks again. */
    fun closed() = settle(allowed = false, refused = false, next = null)

    /** The user closed the refusal, or went to the settings from it. */
    fun dismissed() {
        synchronized(lock) { if (shown.value == LocalNetworkPrompt.Denied) shown.value = null }
    }

    private fun settle(allowed: Boolean, refused: Boolean, next: LocalNetworkPrompt?) {
        val answer = synchronized(lock) {
            if (refused) denied = true
            shown.value = next
            pending.also { pending = null }
        }
        answer?.complete(allowed)
    }

    /** A caller of [ask] stopped waiting; when none is left and the reason is still shown, it no longer applies. */
    private fun withdraw(answer: CompletableDeferred<Boolean>) {
        synchronized(lock) {
            if (pending !== answer || --waiting > 0) return
            if (shown.value == LocalNetworkPrompt.Ask) {
                pending = null
                shown.value = null
            }
        }
    }
}
