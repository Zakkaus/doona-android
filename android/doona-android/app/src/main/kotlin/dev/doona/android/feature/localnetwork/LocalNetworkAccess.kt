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
 * the reason with back or a tap outside it is no refusal. The refusal shown for the running tunnel's report and the one
 * shown for anything else are kept apart: the tunnel's comes down when its report clears, the other only when the user
 * closes it. An action whose failure showed the refusal waits on it: until the user closes it, or, when they go from
 * it to the app's settings, until the app is in front again (see [allowedInSettings]). The app shows [prompt] and
 * reports what the user did; [granted] reads the permission, true where Android has none.
 */
class LocalNetworkAccess(private val granted: () -> Boolean) {
    private val shown = MutableStateFlow<LocalNetworkPrompt?>(null)
    val prompt: StateFlow<LocalNetworkPrompt?> = shown.asStateFlow()

    private val lock = Any()
    private var denied = false
    private var pending: CompletableDeferred<Boolean>? = null

    /** The callers still waiting for [pending]. */
    private var waiting = 0

    /** Whether the reason is shown: from a new question until the user goes on from it or it is withdrawn. */
    private var asking = false

    /** The refusal shown for the tunnel's report, until the report clears or the user closes it. */
    private var tunnelRefusal = false

    /** The refusal shown for an action or the system's answer, until the user closes it. */
    private var refusal = false

    /** The answer the actions waiting on [refusal] share: whether access was allowed in the settings. */
    private var settings: CompletableDeferred<Boolean>? = null

    /** The user went to the settings from the refusal while an action waited on it; [resumed] ends the trip. */
    private var away = false

    /**
     * Called after a user's action failed on the local network. Returns true when the user has just allowed access, so
     * the action is worth one more try; false when it was already allowed (the failure has another cause), or when the
     * user refuses now or refused earlier in this process. Callers that arrive while a question is open share its answer.
     * When the last of them is cancelled while the reason is still shown, the reason is withdrawn and nothing is refused;
     * once the user went on to the system's question, its answer still counts.
     */
    suspend fun ask(): Boolean = ask(tunnel = false)

    private suspend fun ask(tunnel: Boolean): Boolean {
        if (granted()) return false
        val answer = synchronized(lock) {
            if (denied) {
                if (tunnel) tunnelRefusal = true else refusal = true
                show()
                return false
            }
            val open = pending ?: CompletableDeferred<Boolean>().also {
                pending = it
                waiting = 0
                asking = true
                show()
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
     * again here stays only while the caller waits, so it comes down when the tunnel's report clears; a refusal shown for
     * something else stays.
     */
    suspend fun noticed() {
        if (ask(tunnel = true) || !synchronized(lock) { tunnelRefusal }) return
        try {
            awaitCancellation()
        } finally {
            synchronized(lock) {
                tunnelRefusal = false
                show()
            }
        }
    }

    /**
     * The user went on from doona's reason to the system's question; the reason is no longer shown. False when the
     * reason was already withdrawn or answered, as when the tunnel's report cleared as the user tapped: then the system's
     * question is not to be asked.
     */
    fun requesting(): Boolean = synchronized(lock) {
        val open = asking && pending != null
        asking = false
        show()
        open
    }

    /** The system's answer. A refusal shows why doona cannot reach the local network and how to allow it. */
    fun answered(allowed: Boolean) = settle(allowed, refused = !allowed, showRefusal = !allowed)

    /** The user turned down doona's reason with its button: a refusal, without a second dialog about it. */
    fun declined() = settle(allowed = false, refused = true, showRefusal = false)

    /** The user closed doona's reason with back or a tap outside it: no retry now, and the next failure asks again. */
    fun closed() = settle(allowed = false, refused = false, showRefusal = false)

    /**
     * Called after [ask] returned false. While the refusal shown for an action is up, waits for the user: true once they
     * come back from the app's settings with access allowed, so the action is worth one more try; false when they close
     * the refusal, or come back without allowing access. False at once when access is allowed or no such refusal is up.
     */
    suspend fun allowedInSettings(): Boolean {
        if (granted()) return false
        val answer = synchronized(lock) {
            if (!refusal && !away) return false
            settings ?: CompletableDeferred<Boolean>().also { settings = it }
        }
        return answer.await()
    }

    /** The user closed the refusal: the actions waiting on it keep their failure. */
    fun dismissed() = closeRefusal(toSettings = false)

    /** The user went from the refusal to the app's settings: the actions waiting on it wait for [resumed]. */
    fun toSettings() = closeRefusal(toSettings = true)

    /** The app is in front again; after a trip to the settings, the actions that waited learn whether access is allowed. */
    fun resumed() {
        val answer = synchronized(lock) {
            if (!away) return
            away = false
            settings.also { settings = null }
        }
        answer?.complete(granted())
    }

    private fun closeRefusal(toSettings: Boolean) {
        val answer = synchronized(lock) {
            tunnelRefusal = false
            refusal = false
            show()
            if (toSettings && settings != null) away = true
            if (away) null else settings.also { settings = null }
        }
        answer?.complete(false)
    }

    private fun settle(allowed: Boolean, refused: Boolean, showRefusal: Boolean) {
        val answer = synchronized(lock) {
            if (refused) denied = true
            asking = false
            if (showRefusal) refusal = true
            show()
            pending.also { pending = null }
        }
        answer?.complete(allowed)
    }

    /** Publishes what is shown now; called under [lock]. */
    private fun show() {
        shown.value = when {
            asking -> LocalNetworkPrompt.Ask
            refusal || tunnelRefusal -> LocalNetworkPrompt.Denied
            else -> null
        }
    }

    /** A caller of [ask] stopped waiting; when none is left and the reason is still shown, it no longer applies. */
    private fun withdraw(answer: CompletableDeferred<Boolean>) {
        synchronized(lock) {
            if (pending !== answer || --waiting > 0) return
            if (asking) {
                pending = null
                asking = false
                show()
            }
        }
    }
}
