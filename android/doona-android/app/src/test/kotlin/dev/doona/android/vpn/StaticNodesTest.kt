package dev.doona.android.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Member
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.StaticNode
import dev.doona.android.engine.StaticNodeDraft
import dev.doona.android.engine.StaticNodeDraftException
import dev.doona.android.engine.StaticNodeReason
import dev.doona.android.engine.StaticNodeResult
import dev.doona.android.engine.StaticNodeUpdateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaticNodesTest {
    @get:Rule val folder = TemporaryFolder()

    private val core = FakeCore()
    private val hk = StaticNode("n-b91256dabac65282", "HK", "socks5://192.0.2.1:1080#HK")
    private val tokyo = StaticNode("n-fd10541db250550c", "Tokyo", "trojan://demo@192.0.2.2:443")

    @Test
    fun editorSessionPreservesRowOrderAndPublishesEachCommit() = runTest {
        val engine = engine()
        val session = Json.decodeFromString<List<StaticNodeCallFixture>>(ContractFixtures.read("static-nodes.json")).iterator()
        fun serve(fixture: StaticNodeCallFixture = session.next()) = fixture.also { core.reply = it.reply.toString() }
        val preview = serve()
        val link = StaticNodeDraft.Link(hk.link)
        assertEquals(listOf(StaticNodeResult.Ready(hk.id, hk.name, "socks5")), engine.previewStaticNodes(listOf(link)).getOrThrow())
        preview.checkCall()
        val notYetAdded = serve()
        assertEquals(ErrorCode.NotFound, engine.readStaticNode(hk.id).errorCode())
        notYetAdded.checkCall()
        assertEquals(0, core.snapshots.get())
        assertNull(engine.catalog.value)

        val add = serve(session.next().withoutRow(6))
        serveCatalog(8, hk, tokyo)
        assertEquals(batchResults(), engine.addStaticNodes(batchDrafts(link)).getOrThrow())
        add.checkCall()
        engine.checkCatalog(hk, tokyo)

        val read = serve()
        assertEquals(tokyo, engine.readStaticNode(tokyo.id).getOrThrow())
        read.checkCall()

        val invalidName = serve()
        val nameResult: StaticNodeUpdateResult = engine.updateStaticNode(hk, StaticNodeDraft.Link("socks5://192.0.2.1:1080", name = " ")).getOrThrow()
        assertEquals(StaticNodeResult.Invalid(StaticNodeReason.Name), nameResult)
        invalidName.checkCall()

        val unsupportedPath = serve()
        val pathResult = engine.updateStaticNode(hk, StaticNodeDraft.Link("socks5://192.0.2.1:1080/path")).getOrThrow()
        assertEquals(StaticNodeResult.Unsupported(StaticNodeReason.Path), pathResult)
        unsupportedPath.checkCall()

        val duplicate = serve()
        assertEquals(StaticNodeResult.Duplicate(tokyo.id), engine.updateStaticNode(hk, StaticNodeDraft.Link(tokyo.link)).getOrThrow())
        duplicate.checkCall()
        engine.checkCatalog(hk, tokyo)

        val rename = serve()
        val renamed = hk.copy(name = "Renamed")
        serveCatalog(9, renamed, tokyo)
        assertEquals(StaticNodeResult.Updated(hk.id, renamed.name, "socks5"), engine.updateStaticNode(hk, StaticNodeDraft.Link(hk.link, renamed.name)).getOrThrow())
        rename.checkCall()
        engine.checkCatalog(renamed, tokyo)

        val beforeFailures = core.snapshots.get()
        val stale = serve()
        stale.checkFailure(engine.updateStaticNode(hk, StaticNodeDraft.Link("socks5://192.0.2.3:1080")), ErrorCode.Busy, beforeFailures)

        val staleRemoval = serve()
        staleRemoval.checkFailure(engine.removeStaticNode(hk, confirmed = true), ErrorCode.Busy, beforeFailures)

        val notConfirmed = serve()
        notConfirmed.checkFailure(engine.removeStaticNode(renamed, confirmed = false), ErrorCode.InvalidArgument, beforeFailures)
        engine.checkCatalog(renamed, tokyo)

        val remove = serve()
        serveCatalog(10, tokyo)
        engine.removeStaticNode(renamed, confirmed = true).getOrThrow()
        remove.checkCall()
        engine.checkCatalog(tokyo)

        val afterRemove = core.snapshots.get()
        val repeatedRemoval = serve()
        repeatedRemoval.checkFailure(engine.removeStaticNode(renamed, confirmed = true), ErrorCode.NotFound, afterRemove)
        val removedEdit = serve()
        removedEdit.checkFailure(engine.updateStaticNode(renamed, StaticNodeDraft.Link("socks5://192.0.2.1:1080")), ErrorCode.NotFound, afterRemove)
        engine.checkCatalog(tokyo)

        val fieldErrors = serve(session.next().withoutRow(2))
        val beforePreview = core.snapshots.get()
        assertEquals(fieldPreviewResults(), engine.previewStaticNodes(fieldPreviewDrafts()).getOrThrow())
        fieldErrors.checkCall()
        assertEquals(beforePreview, core.snapshots.get())

        val removed = serve()
        assertEquals(ErrorCode.NotFound, engine.readStaticNode(hk.id).errorCode())
        removed.checkCall()

        checkFragmentAdd(engine, serve())

        assertFalse(session.hasNext())
    }

    @Test
    fun previewAndAddSurfaceInvalidBatchCounts() = runTest {
        val engine = engine()
        val row = StaticNodeDraft.Link(hk.link)
        core.reply = ContractFixtures.read("call-error-static-row-count.json")
        for (count in listOf(0, 201)) {
            val drafts = List(count) { row }
            assertEquals(ErrorCode.InvalidArgument, engine.previewStaticNodes(drafts).errorCode())
            assertEquals(ErrorCode.InvalidArgument, engine.addStaticNodes(drafts).errorCode())
        }
        assertEquals(listOf(0, 0, 201, 201), core.calls.map { (_, args) -> Json.parseToJsonElement(args).jsonObject.getValue("rows").jsonArray.size })
        assertEquals(0, core.snapshots.get())
    }

    @Test
    fun singleManualDraftKeepsOptionsAndSurfacesFailedWrite() = runTest {
        val engine = engine()
        core.reply = """{"ok":{"rows":[{"status":"added","id":"${hk.id}","name":"HK","protocol":"socks5"}]}}"""
        serveCatalog(8, hk)
        assertEquals(listOf(StaticNodeResult.Added(hk.id, hk.name, "socks5")), engine.addStaticNodes(listOf(StaticNodeDraft.Link(hk.link))).getOrThrow())
        engine.checkCatalog(hk)

        val uuid = "00000000-0000-4000-8000-000000000001"
        val draft = StaticNodeDraft.Manual.create("vless", "192.0.2.2", 443, uuid = uuid, options = mapOf("type" to "ws", "path" to "/test")).getOrThrow()
        val beforeFailure = core.snapshots.get()
        core.reply = ContractFixtures.read("call-error-state-write.json")
        assertEquals(ErrorCode.Internal, engine.addStaticNodes(listOf(draft)).errorCode())
        assertEquals(beforeFailure, core.snapshots.get())
        val (command, args) = core.calls.last()
        assertEquals("addStaticNodes", command)
        assertEquals(
            Json.parseToJsonElement(
                """{"rows":[{"kind":"manual","protocol":"vless","server":"192.0.2.2","port":443,"uuid":"$uuid","options":{"type":"ws","path":"/test"}}]}""",
            ),
            Json.parseToJsonElement(args),
        )
        engine.checkCatalog(hk)
    }

    @Test
    fun updateRejectsBatchOnlyReplyStatusesWithoutPolling() = runTest {
        val engine = engine()
        for (status in listOf("ready", "added")) {
            core.reply = """{"ok":{"status":"$status","id":"${hk.id}","name":"HK","protocol":"socks5"}}"""
            assertEquals(ErrorCode.Internal, engine.updateStaticNode(hk, StaticNodeDraft.Link(hk.link)).errorCode())
            assertEquals(0, core.snapshots.get())
        }
    }

    @Test
    fun manualPortBoundsAreSentAndOutOfRangePortsAreFieldErrors() = runTest {
        val engine = engine()
        for (port in listOf(0, 65536)) {
            val preview = StaticNodeDraft.Manual.create("socks5", "192.0.2.1", port)
                .map { engine.previewStaticNodes(listOf(it)).getOrThrow() }
            assertEquals(StaticNodeReason.Port, (preview.exceptionOrNull() as? StaticNodeDraftException)?.reason)
        }
        assertEquals(0, core.calls.size)

        val drafts = listOf(1, 65535).map { port -> StaticNodeDraft.Manual.create("socks5", "192.0.2.1", port).getOrThrow() }
        core.reply = """{"ok":{"rows":[{"status":"ready","id":"n-low","name":"Low","protocol":"socks5"},{"status":"ready","id":"n-high","name":"High","protocol":"socks5"}]}}"""
        engine.previewStaticNodes(drafts).getOrThrow()
        val (_, args) = core.calls.single()
        val rows = Json.parseToJsonElement(args).jsonObject.getValue("rows").jsonArray
        assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(65535)), rows.map { it.jsonObject.getValue("port") })
    }

    private fun TestScope.engine(): ServiceEngine {
        val scope = backgroundScope
        val io = StandardTestDispatcher(testScheduler)
        val opener = CoreOpener(core, { folder.root.path }, io)
        val settings = PerAppSettings(PerAppSettings.store(scope) { folder.root.resolve("per_app.preferences_pb") })
        val controller = TunnelController(core, opener, { error("Tunnel not used") }, scope, killProcess = {}, endDebugCapture = {}, expireDebugCapture = { null })
        return ServiceEngine(core, opener, controller, settings, MutableStateFlow(NetworkState()), { error("Tunnel not used") }, io, scope, expireDebugCapture = { null })
    }

    private fun serveCatalog(revision: Long, vararg nodes: StaticNode) {
        val changed = snapshot(revision, *nodes)
        core.snapshotReply = { since -> if (since < revision) changed else unchanged(revision) }
    }

    private suspend fun checkFragmentAdd(engine: ServiceEngine, fixture: StaticNodeCallFixture) {
        val rawFragment = StaticNodeDraft.Link("socks5://192.0.2.1:1080#HK->JP")
        val encodedFragment = StaticNodeDraft.Link("socks5://192.0.2.1:1080#HK-%3EJP")
        val restored = hk.copy(name = "HK->JP", link = rawFragment.link)
        serveCatalog(11, tokyo, restored)
        assertEquals(
            listOf(StaticNodeResult.Added(hk.id, restored.name, "socks5"), StaticNodeResult.Duplicate(hk.id)),
            engine.addStaticNodes(listOf(rawFragment, encodedFragment)).getOrThrow(),
        )
        fixture.checkCall()
        engine.checkCatalog(tokyo, restored)
    }

    private fun batchDrafts(link: StaticNodeDraft.Link) = listOf(
        link,
        StaticNodeDraft.Link("socks5://192.0.2.1:1080#same"),
        StaticNodeDraft.Link("not a link"),
        StaticNodeDraft.Link("socks5://192.0.2.3:1080 -> socks5://192.0.2.1:1080"),
        StaticNodeDraft.Manual.create("trojan", "192.0.2.2", 443, name = tokyo.name, password = "demo").getOrThrow(),
        StaticNodeDraft.Manual.create("vless", "192.0.2.2", 443, uuid = "bad").getOrThrow(),
        StaticNodeDraft.Link("trojan://demo@192.0.2.2:443?unknown=1"),
        StaticNodeDraft.Link("other://192.0.2.1"),
    )

    private fun batchResults() = listOf(
        StaticNodeResult.Added(hk.id, hk.name, "socks5"),
        StaticNodeResult.Duplicate(hk.id),
        StaticNodeResult.Invalid(StaticNodeReason.Link),
        StaticNodeResult.Unsupported(StaticNodeReason.Chain),
        StaticNodeResult.Added(tokyo.id, tokyo.name, "trojan"),
        StaticNodeResult.Invalid(StaticNodeReason.Uuid),
        StaticNodeResult.Unsupported(StaticNodeReason.Option),
        StaticNodeResult.Unsupported(StaticNodeReason.Protocol),
    )

    private fun fieldPreviewDrafts() = listOf(
        StaticNodeDraft.Link("socks5://192.0.2.1:1080#HK-%3EJP"),
        StaticNodeDraft.Manual.create("trojan", "", 443).getOrThrow(),
        StaticNodeDraft.Manual.create("trojan", "192.0.2.1", 443).getOrThrow(),
        StaticNodeDraft.Manual.create("trojan", "192.0.2.1", 443, username = "u").getOrThrow(),
        StaticNodeDraft.Manual.create("vless", "192.0.2.1", 443, password = "p").getOrThrow(),
        StaticNodeDraft.Manual.create("socks5", "192.0.2.1", 1080, uuid = "u").getOrThrow(),
        StaticNodeDraft.Manual.create("ss", "192.0.2.1", 443, password = "p").getOrThrow(),
        StaticNodeDraft.Manual.create("ss", "192.0.2.1", 443, password = "p", cipher = "obsolete").getOrThrow(),
        StaticNodeDraft.Link("socks5://user@192.0.2.1:1080"),
        StaticNodeDraft.Link("trojan://demo@192.0.2.1:443?sni=a&sni=b"),
    )

    private fun fieldPreviewResults() = listOf(
        StaticNodeResult.Ready(hk.id, "HK->JP", "socks5"),
        StaticNodeResult.Invalid(StaticNodeReason.Server),
        StaticNodeResult.Invalid(StaticNodeReason.Password),
        StaticNodeResult.Unsupported(StaticNodeReason.Username),
        StaticNodeResult.Unsupported(StaticNodeReason.Password),
        StaticNodeResult.Unsupported(StaticNodeReason.Uuid),
        StaticNodeResult.Invalid(StaticNodeReason.Cipher),
        StaticNodeResult.Unsupported(StaticNodeReason.Cipher),
        StaticNodeResult.Invalid(StaticNodeReason.Credentials),
        StaticNodeResult.Invalid(StaticNodeReason.DuplicateOption),
    )

    // The core fixtures also exercise invalid ports that Kotlin drafts cannot represent.
    private fun StaticNodeCallFixture.withoutRow(index: Int): StaticNodeCallFixture {
        fun JsonObject.trimRows() = JsonObject(this + ("rows" to JsonArray(getValue("rows").jsonArray.filterIndexed { i, _ -> i != index })))
        return copy(args = args.trimRows(), reply = JsonObject(mapOf("ok" to reply.getValue("ok").jsonObject.trimRows())))
    }

    private fun StaticNodeCallFixture.checkCall() {
        val (actualCommand, actualArgs) = core.calls.last()
        assertEquals(command, actualCommand)
        assertEquals(args, Json.parseToJsonElement(actualArgs))
    }

    private fun StaticNodeCallFixture.checkFailure(result: Result<*>, code: ErrorCode, snapshotsBefore: Int) {
        assertEquals(code, result.errorCode())
        checkCall()
        assertEquals(snapshotsBefore, core.snapshots.get())
    }

    private fun ServiceEngine.checkCatalog(vararg nodes: StaticNode) {
        val catalog = checkNotNull(catalog.value)
        for (groupId in listOf("g-Proxy", "g-Auto")) {
            val leaves = catalog.groups.single { it.id == groupId }.members.filterNot { it.isGroup }
            assertEquals(nodes.map { Member(it.id, it.name) }, leaves.filter { it.subscriptionId == null })
            assertEquals(setOf("s1"), leaves.mapNotNull { it.subscriptionId }.toSet())
        }
        assertEquals(catalog.groups, groups.value)
        assertEquals(listOf("s1"), subscriptions.value.map { it.id })
    }

    private fun snapshot(revision: Long, vararg nodes: StaticNode): String {
        val base = Json.parseToJsonElement(ContractFixtures.read("snapshot-stopped-catalog.json")).jsonObject
        val catalog = base.getValue("catalog").jsonObject
        val local = nodes.map {
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(it.id),
                    "name" to JsonPrimitive(it.name),
                    "failed" to JsonPrimitive(false),
                    "isGroup" to JsonPrimitive(false),
                    "subscriptionId" to JsonNull,
                ),
            )
        }
        val groups = catalog.getValue("groups").jsonArray.map { group ->
            val obj = group.jsonObject
            if (obj["id"] !in listOf(JsonPrimitive("g-Proxy"), JsonPrimitive("g-Auto"))) return@map group
            JsonObject(obj + ("members" to JsonArray(obj.getValue("members").jsonArray + local)))
        }
        return JsonObject(base + mapOf("revision" to JsonPrimitive(revision), "catalog" to JsonObject(catalog + ("groups" to JsonArray(groups))))).toString()
    }

    private fun unchanged(revision: Long) =
        """{"revision":$revision,"running":false,"generation":0,"traffic":{"upTotal":0,"downTotal":0,"activeFlows":0}}"""

    private fun Result<*>.errorCode() = (exceptionOrNull() as EngineException).code
}

@Serializable
internal data class StaticNodeCallFixture(val command: String, val args: JsonObject, val reply: JsonObject)
