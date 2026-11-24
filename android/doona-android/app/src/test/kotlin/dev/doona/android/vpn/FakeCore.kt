package dev.doona.android.vpn

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** [ServiceEngineTest]'s core: it serves the contract fixtures, logs every call, and can hold, fail or refuse them. */
internal class FakeCore : CoreBridge {
    @Volatile var reply = """{"ok":{}}"""

    @Volatile var schema = CORE_SCHEMA

    @Volatile var callGate: CountDownLatch? = null

    @Volatile var callError: Throwable? = null

    @Volatile var snapshotError: Throwable? = null
    val callEntered = CountDownLatch(1)

    /** Every call that reached the core, logged before [callGate] holds it. */
    val entered = CopyOnWriteArrayList<String>()
    val calls = CopyOnWriteArrayList<Pair<String, String>>()
    val snapshots = AtomicInteger()

    /** Served first, in order, before the fixtures. */
    val snapshotReplies = ConcurrentLinkedQueue<() -> String>()

    @Volatile var snapshotReply: ((sinceRevision: Long) -> String)? = null

    /** Whether the running fixture keeps its `local_network` warning. */
    @Volatile var warns = true

    /** Runs as the core starts a generation. */
    @Volatile var onStart: () -> Unit = {}

    override fun init(dataDir: String) = """{"schema":$schema}"""

    override fun start(tunFd: Int, configJson: String) = onStart()

    override fun stop() = Unit

    // The catalog fixture is revision 7; the running fixture is the same revision without a catalog.
    override fun snapshot(sinceRevision: Long): String {
        snapshots.incrementAndGet()
        snapshotError?.let { throw it }
        snapshotReplies.poll()?.let { return it() }
        snapshotReply?.let { return it(sinceRevision) }
        val fixture = ContractFixtures.read(if (sinceRevision < 7) "snapshot-stopped-catalog.json" else "snapshot-running.json")
        return if (warns) fixture else fixture.replace("\"warning\": \"local_network\"", "\"warning\": null")
    }

    override fun call(command: String, argsJson: String): String {
        entered += command
        callEntered.countDown()
        callGate?.await()
        calls += command to argsJson
        callError?.let { throw it }
        return reply
    }
}
