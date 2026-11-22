package dev.doona.android.engine.fake

import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Settings
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.Traffic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.sin

/** Deterministic in-memory [Engine] for previews and screenshot tests; it never touches the network or a tunnel. */
class FakeEngine(
    groups: List<Group> = SAMPLE_GROUPS,
    subscriptions: List<Subscription> = SAMPLE_SUBSCRIPTIONS,
    status: Status = Status.Stopped,
    traffic: Traffic = Traffic(),
) : Engine {
    override val ready = MutableStateFlow(true)
    override val status = MutableStateFlow(status)
    override val traffic = MutableStateFlow(traffic)
    override val groups = MutableStateFlow(groups)
    override val subscriptions = MutableStateFlow(subscriptions)
    override val settings: StateFlow<Settings> get() = settingsFlow
    private val settingsFlow = MutableStateFlow(Settings())

    /** True: the fake's policy is there from the start; a test sets false to stand for a stored policy still being read. */
    override val perAppLoaded = MutableStateFlow(true)

    /** None: the fake's tunnel never dials. A test sets one to stand for the core's report. */
    override val warning = MutableStateFlow<ErrorCode?>(null)

    override fun connect() {
        status.value = Status.Running(sinceEpochMs = 0)
    }

    override fun disconnect() {
        status.value = Status.Stopped
    }

    override suspend fun select(groupId: String, memberId: String?): Result<Unit> {
        groups.update { all ->
            all.map { g ->
                if (g.id != groupId) g else g.copy(selectedId = memberId ?: g.members.firstOrNull()?.id, pinned = memberId != null)
            }
        }
        return Result.success(Unit)
    }

    override suspend fun testLatency(groupId: String?): Result<Unit> = Result.success(Unit)

    override suspend fun addSubscription(url: String, name: String?): Result<Subscription> {
        val added = Subscription(id = "s${subscriptions.value.size + 1}", name = name ?: url, url = url, nodes = 0, updatedAtEpochMs = 0)
        subscriptions.update { it + added }
        return Result.success(added)
    }

    override suspend fun updateSubscription(id: String): Result<Subscription> =
        Result.success(subscriptions.value.first { it.id == id })

    override suspend fun removeSubscription(id: String): Result<Unit> {
        subscriptions.update { all -> all.filterNot { it.id == id } }
        return Result.success(Unit)
    }

    override suspend fun setMode(mode: Mode): Result<Unit> {
        settingsFlow.update { it.copy(mode = mode) }
        return Result.success(Unit)
    }

    override suspend fun setPerApp(perApp: PerApp): Result<Unit> {
        settingsFlow.update { it.copy(perApp = perApp) }
        return Result.success(Unit)
    }

    companion object {
        private fun node(id: String, name: String, latencyMs: Int?) =
            Member(id = id, name = name, latencyMs = latencyMs, subscriptionId = "s1")

        private val NODES = listOf(node("n-hk1", "hk-01", 42), node("n-hk2", "hk-02", 57), node("n-jp1", "jp-01", 88), node("n-us1", "us-01", 171))

        val SAMPLE_GROUPS = listOf(
            Group(
                id = "g-Proxy",
                name = "Proxy",
                kind = GroupKind.Selector,
                members = listOf(Member(id = "g-Auto", name = "Auto", isGroup = true), Member(id = "g-HK", name = "HK", isGroup = true)) + NODES,
                selectedId = "g-Auto",
            ),
            Group(id = "g-Auto", name = "Auto", kind = GroupKind.UrlTest, members = NODES, selectedId = "n-hk1"),
            Group(id = "g-HK", name = "HK", kind = GroupKind.UrlTest, members = NODES.take(2), selectedId = "n-hk1"),
        )

        /** A minute of download near 3.9 MB/s and upload near 330 KB/s: two slow waves around each level. */
        val SAMPLE_TRAFFIC: Traffic = run {
            fun series(level: Double, swing: Double, seed: Int) = List(60) { i ->
                val t = (i + seed).toDouble()
                (level + swing * (0.6 * sin(t / 9.0) + 0.4 * sin(t / 3.4 + seed))).toLong()
            }
            val down = series(3_900_000.0, 500_000.0, 1)
            val up = series(330_000.0, 60_000.0, 5)
            Traffic(upRate = 330_000, downRate = 3_900_000, upTotal = up.sum(), downTotal = down.sum(), upHistory = up, downHistory = down)
        }

        val SAMPLE_SUBSCRIPTIONS = listOf(
            Subscription(id = "s1", name = "harbor", url = "https://example.invalid/sub", nodes = 4, updatedAtEpochMs = 0),
        )
    }
}
