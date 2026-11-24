package dev.doona.android.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs the core's `init` before any other call; a failed `init` is retried by the next caller. */
internal class CoreOpener(private val core: CoreBridge, private val dataDir: () -> String, private val io: CoroutineDispatcher) {
    private val mutex = Mutex()
    private val initialized = MutableStateFlow<Int?>(null)

    @Volatile
    private var disabled = false

    val openedSchema: Int? get() = if (disabled) null else initialized.value

    suspend fun awaitSchema(): Int = initialized.filterNotNull().first()

    /** The core's contract schema, or null when the library or `init` failed, or since the core was [disable]d. */
    suspend fun schema(): Int? = if (disabled) {
        null
    } else {
        mutex.withLock { initialized.value ?: open()?.also { initialized.value = it } }
    }

    /** A native function failed to link: the core counts as one whose `init` failed from now on, without a retry. */
    fun disable() {
        disabled = true
    }

    private suspend fun open(): Int? = try {
        withContext(io) { CoreJson.schema(core.init(dataDir())) }
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
