package dev.doona.android.report

import android.os.Process
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Member
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFetch
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.vpn.NativeApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.zip.ZipFile

/** What the export's tests share: the directories, a honk whose catalog is full of secrets, an export over it, and its zip. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ExportFixture {
    @get:Rule
    val tmp = TemporaryFolder()

    protected val crashDir: File get() = tmp.root.resolve("crash")
    protected val outDir: File get() = tmp.root.resolve("reports")

    /** The exports a sheet may call back for, in a file that outlives an export object, as it outlives a process. */
    protected val tokensFile: File get() = tmp.root.resolve("report-exports.json")
    protected val tokens: ExportTokens get() = ExportTokens { tokensFile }

    @Before
    fun setUp() = AppEvents.clear()

    /** What a provider's subscription and a user's catalog can hold: a token URL, servers by address and by name. */
    private val token = "tk_9f8e7d6c5b4a"
    protected val secrets = listOf(token, "provider-example.net", "203.0.113.45", "198.51.100.7", "hk1.edge-example.org", "2001:db8::17", "harbor-home", "user:pass")

    /** The engine beside honk ([ReportHonk.secrets]): the mode and an app list by name, which the report counts. */
    protected suspend fun fixtureEngine(): FakeEngine = FakeEngine().apply { setPerApp(PerApp(AppPolicy.OnlyListed, setOf("com.example.secretapp"))) }

    protected val app = AppInfo("0.1", 1, "debug", listOf("arm64-v8a"), "16", 36, "Google", "Pixel 9")

    protected fun TestScope.export(
        engine: Engine,
        now: Long = 1_795_300_200_000,
        scope: CoroutineScope = this,
        logLevels: LogLevelController = LogLevelController({ engine.setLogLevel(LogLevel.valueOf(it.name)) }, scope) { testScheduler.currentTime },
        ownAddresses: () -> List<InetAddress> = { emptyList() },
        flowEvents: FlowEvents = testFlowEvents(StandardTestDispatcher(testScheduler)),
        honk: NativeApi = ReportHonk.secrets(),
    ): ReportExport {
        val files = CrashFiles(crashDir)
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ReportExport(engine, honk, files, CrashOffer(files, tmp.root.resolve("seen")), { outDir }, tokens, { app }, scope, dispatcher, logLevels, flowEvents, ownAddresses) { now }
    }

    /** Every entry of the zip, by name. */
    protected fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().decodeToString() }
    }

    /** A crash file of the app, from an exception whose message quotes the subscription URL. */
    protected fun appCrash(at: Long): String {
        val error = IllegalStateException("fetch https://sub.provider-example.net/api?token=$token failed")
        CrashHandler(CrashFiles(crashDir, clock = { at }), "0.1 debug arm64-v8a-android", { _, _ -> }, waitMs = 10_000) { true }.uncaughtException(Thread.currentThread(), error)
        return CrashFiles(crashDir).list().last().name
    }
}

internal fun testFlowEvents(io: CoroutineDispatcher): FlowEvents = FlowEvents(ReportHonk.down(), io, { _, _, _ -> Process.INVALID_UID }, { null })

/** Records the clears it is sent; the first [failures] fail, and with [hangs] none returns, as in a process that ends. */
internal class Recording(
    private val fake: FakeEngine,
    private val coreUp: Boolean = true,
    private var failures: Int = 0,
    private val hangs: Boolean = false,
) : Engine by fake {
    val cleared = mutableListOf<Pair<List<String>, Long>>()

    override suspend fun diagnostics(level: LogLevel?) =
        if (coreUp) fake.diagnostics(level) else Result.failure(EngineException(ErrorCode.Internal))

    override suspend fun clearCrashes(names: List<String>, mark: Long): Result<Unit> {
        cleared += names to mark
        if (hangs) awaitCancellation()
        if (failures > 0) {
            failures--
            return Result.failure(EngineException(ErrorCode.Internal))
        }
        return fake.clearCrashes(names, mark)
    }
}
