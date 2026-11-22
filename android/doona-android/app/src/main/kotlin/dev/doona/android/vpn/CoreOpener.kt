package dev.doona.android.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs the core's `init` before any other call; a failed `init` is retried by the next caller. */
internal class CoreOpener(private val core: CoreBridge, private val dataDir: String, private val io: CoroutineDispatcher) {
    private val mutex = Mutex()
    private var schema: Int? = null

    @Volatile
    private var disabled = false

    /** The core's contract schema, or null when the library or `init` failed, or since the core was [disable]d. */
    suspend fun schema(): Int? = if (disabled) {
        null
    } else {
        mutex.withLock { schema ?: open()?.also { schema = it } }
    }

    /** A native function failed to link: the core counts as one whose `init` failed from now on, without a retry. */
    fun disable() {
        disabled = true
    }

    private suspend fun open(): Int? = try {
        withContext(io) { CoreJson.schema(core.init(dataDir)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        VpnLog.warn("init failed: ${e.javaClass.simpleName}")
        null
    } catch (e: LinkageError) {
        VpnLog.warn("init failed: ${e.javaClass.simpleName}")
        null
    }
}
