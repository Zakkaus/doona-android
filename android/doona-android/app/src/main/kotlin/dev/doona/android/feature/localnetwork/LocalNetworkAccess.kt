package dev.doona.android.feature.localnetwork

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the app shows about local network access: the one-line reason before the system's question, or a refusal. */
enum class LocalNetworkPrompt { Ask, Denied }

/**
 * The `ACCESS_LOCAL_NETWORK` permission, asked for only after something on the local network failed (ARCH D26). The
 * question comes at most once per process: a refusal, in doona's reason or in the system's dialog, is final until the
 * process ends, and a later failure only shows [LocalNetworkPrompt.Denied], which leads to the app's settings. The app
 * shows [prompt] and reports what the user did; [granted] reads the permission, true where Android has none.
 */
class LocalNetworkAccess(private val granted: () -> Boolean) {
    private val shown = MutableStateFlow<LocalNetworkPrompt?>(null)
    val prompt: StateFlow<LocalNetworkPrompt?> = shown.asStateFlow()

    private val lock = Any()
    private var denied = false
    private var pending: CompletableDeferred<Boolean>? = null

    /**
     * Called after a user's action failed on the local network. Returns true when the user has just allowed access, so
     * the action is worth one more try; false when it was already allowed (the failure has another cause), or when the
     * user refuses now or refused earlier in this process. Callers that arrive while a question is open share its answer.
     */
    suspend fun ask(): Boolean {
        if (granted()) return false
        val answer = synchronized(lock) {
            if (denied) {
                shown.value = LocalNetworkPrompt.Denied
                return false
            }
            pending ?: CompletableDeferred<Boolean>().also {
                pending = it
                shown.value = LocalNetworkPrompt.Ask
            }
        }
        return answer.await()
    }

    /** The running tunnel failed to reach a node on the local network: asks unless the user already refused. */
    suspend fun noticed() {
        if (!synchronized(lock) { denied }) ask()
    }

    /** The user went on from doona's reason to the system's question; the reason is no longer shown. */
    fun requesting() {
        synchronized(lock) { if (shown.value == LocalNetworkPrompt.Ask) shown.value = null }
    }

    /** The system's answer. A refusal shows why doona cannot reach the local network and how to allow it. */
    fun answered(allowed: Boolean) = settle(allowed, if (allowed) null else LocalNetworkPrompt.Denied)

    /** The user dismissed doona's reason without going on: a refusal, without a second dialog about it. */
    fun declined() = settle(allowed = false, next = null)

    /** The user closed the refusal, or went to the settings from it. */
    fun dismissed() {
        synchronized(lock) { if (shown.value == LocalNetworkPrompt.Denied) shown.value = null }
    }

    private fun settle(allowed: Boolean, next: LocalNetworkPrompt?) {
        val answer = synchronized(lock) {
            if (!allowed) denied = true
            shown.value = next
            pending.also { pending = null }
        }
        answer?.complete(allowed)
    }
}
