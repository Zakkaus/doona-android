package dev.doona.android.vpn

import dev.doona.android.engine.Status

/**
 * The session one [DoonaVpnService] instance started, followed until it ends. The status alone cannot tell: a start
 * that fails at once can replace [Status.Starting] before the service looks, and a failure that repeats the one before
 * is an equal value. The generation of the start the service took can. Confined to the main thread.
 */
internal class ServiceSession(private val controller: TunnelController) {
    private var generation = NONE

    /** True while a session this instance started has not ended. */
    val open: Boolean get() = generation != NONE

    /** Takes the start the engine requested as [requested]; ignored when a stop or a newer command replaced it. */
    fun start(host: TunHost, requested: Long, startBlock: VpnStartBlock? = null) {
        if (controller.start(host, requested, startBlock)) generation = requested
    }

    fun startSystem(host: TunHost, startBlock: VpnStartBlock? = null, cancelRestart: () -> Unit): Boolean {
        generation = controller.startSystem(host, startBlock, cancelRestart) ?: return false
        return true
    }

    /** Calls [end] once for each session this instance started, with its terminal status. */
    suspend fun follow(end: (Session) -> Unit) {
        controller.session.collect { session ->
            if (open && session.generation >= generation && session.status.ended && !session.recovering) {
                generation = NONE
                end(session)
            }
        }
    }

    private companion object {
        const val NONE = 0L
    }
}
