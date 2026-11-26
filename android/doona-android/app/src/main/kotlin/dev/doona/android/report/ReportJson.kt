package dev.doona.android.report

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.Member
import dev.doona.android.engine.NodeCodes
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.Settings
import dev.doona.android.engine.Status
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress

/** What the report states of the build and the device. */
data class AppInfo(
    val version: String,
    val versionCode: Long,
    val buildType: String,
    val abis: List<String>,
    val android: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
) {
    /**
     * The app's line in a crash file, as the core writes its own: version, build type, ABI. Only the grammar the export
     * keeps: a part that is not a version name ([VERSION_NAME]), a build type or an ABI Android names is [UNKNOWN].
     */
    val crashVersion: String get() {
        val name = version.takeIf(VERSION_NAME::matches) ?: UNKNOWN
        val build = buildType.takeIf { it in BUILD_TYPES } ?: UNKNOWN
        val abi = abis.firstOrNull()?.takeIf { it in ABIS } ?: UNKNOWN
        return "$name $build $abi-android"
    }

    companion object {
        fun of(context: Context): AppInfo {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            return AppInfo(
                version = info.versionName.orEmpty(),
                versionCode = info.longVersionCode,
                buildType = if (debug) "debug" else "release",
                abis = Build.SUPPORTED_ABIS.toList(),
                android = Build.VERSION.RELEASE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
            )
        }
    }
}

internal data class EngineView(
    val status: Status,
    val settings: Settings,
    val catalog: Catalog?,
    val members: List<Member>,
    val endpoints: Map<String, NodeInfo>,
)

/** The report format's version, for whoever reads `report.json`. */
private const val REPORT_FORMAT = 1

/** At most this many nodes are listed one by one; the counts cover them all. */
internal const val MAX_LISTED_NODES = 1000

private val reportJson = Json { prettyPrint = true }

internal fun reportJson(
    atEpochMs: Long,
    app: AppInfo,
    engine: EngineView,
    diagnostics: JsonObject,
    events: List<AppEvent>,
    crashes: List<String>,
    addresses: List<InetAddress>,
    scrubber: ReportScrubber,
): String {
    val report = buildJsonObject {
        put("format", REPORT_FORMAT)
        put("atEpochMs", atEpochMs)
        putJsonObject("app") {
            put("version", app.version)
            put("versionCode", app.versionCode)
            put("build", app.buildType)
            putJsonArray("abis") { app.abis.forEach { add(it) } }
        }
        put("core", diagnostics["core"] ?: JsonNull)
        putJsonObject("device") {
            put("android", scrubber.metadata(app.android))
            put("sdk", app.sdk)
            put("manufacturer", scrubber.metadata(app.manufacturer))
            put("model", scrubber.metadata(app.model))
            if (addresses.isNotEmpty()) {
                putJsonArray("ownAddresses") {
                    addresses.distinct().forEach { add(scrubber.ownAddress(it)) }
                }
            }
        }
        put("settings", settingsSummary(engine, scrubber))
        put("diagnostics", diagnostics)
        putJsonArray("events") {
            events.forEach { e ->
                addJsonObject {
                    put("atEpochMs", e.atEpochMs)
                    put("level", e.level.name.lowercase())
                    put("text", scrubber.eventText(e))
                    scrubber.domain(e)?.let { put("domain", it) }
                    scrubber.appName(e)?.let { put("appName", it) }
                }
            }
        }
        putJsonArray("crashes") { crashes.forEach { add(it) } }
    }
    return reportJson.encodeToString(JsonObject.serializer(), report)
}

/** The settings by ids, node codes and counts. */
private fun settingsSummary(engine: EngineView, scrubber: ReportScrubber): JsonObject = buildJsonObject {
    putJsonObject("status") { status(engine.status) }
    put("mode", engine.settings.mode.name)
    putJsonObject("perApp") {
        put("policy", engine.settings.perApp.policy.name)
        put("apps", engine.settings.perApp.packages.size)
    }
    put("catalog", engine.catalog?.let { catalogSummary(it, engine, scrubber) } ?: JsonNull)
}

private fun JsonObjectBuilder.status(status: Status) {
    // Named here: a release build renames the classes.
    val state = when (status) {
        Status.Stopped -> "Stopped"
        Status.Starting -> "Starting"
        is Status.Running -> "Running"
        Status.Stopping -> "Stopping"
        is Status.Failed -> "Failed"
    }
    put("state", state)
    if (status is Status.Failed) {
        put("reason", status.reason.name)
        put("code", status.code?.name)
    }
}

private fun catalogSummary(catalog: Catalog, engine: EngineView, scrubber: ReportScrubber): JsonObject = buildJsonObject {
    // A group's id is its name, which a subscription may have chosen: groups go by their place in the catalog.
    val index = catalog.groups.withIndex().associate { it.value.id to it.index }
    put("mode", catalog.mode.name)
    put("mainGroup", index[catalog.mainGroupId])
    putJsonObject("ruleSource") {
        val source = catalog.ruleSource
        // The core names only a template it builds in (setRuleSource refuses any other), never user text.
        put("template", source.template)
        put("version", source.version)
        put("subscription", source.subscriptionId?.let(::subscriptionId))
        put("blockQuic", source.options.blockQuic)
        put("rules", source.rules)
        put("lines", source.lines)
        put("skipped", source.skipped)
        put("error", source.error?.name)
    }
    putJsonArray("subscriptions") {
        catalog.subscriptions.forEach { s ->
            addJsonObject {
                put("id", subscriptionId(s.id))
                put("nodes", s.nodes)
                put("updatedAtEpochMs", s.lastFetch?.atEpochMs)
                // Only whether the last refresh failed, not why.
                put("failed", s.error != null)
                put("quota", s.totalBytes != null)
            }
        }
    }
    putJsonArray("groups") { catalog.groups.forEachIndexed { i, g -> addJsonObject { group(i, g, index) } } }
    nodes(engine.members, engine.endpoints, scrubber)
}

internal fun Catalog.nodeMembers(): List<Member> = groups.asSequence().flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }.toList()

private fun JsonObjectBuilder.group(at: Int, group: Group, index: Map<String, Int>) {
    put("index", at)
    put("kind", group.kind.name)
    put("members", group.members.size)
    put("nodes", group.members.count { !it.isGroup })
    put("pinned", group.pinned)
    put("testing", group.testing)
    val tcpSelected = group.selectedCode(group.tcp.selectedId, index)
    put("selected", tcpSelected)
    put("tcpSelected", tcpSelected)
    put("tcpPinned", group.tcp.pinned)
    put("udpSelected", group.selectedCode(group.udp.selectedId, index))
    put("udpPinned", group.udp.pinned)
}

private fun Group.selectedCode(selectedId: String?, index: Map<String, Int>): String? {
    val selected = members.firstOrNull { it.id == selectedId } ?: return null
    return if (selected.isGroup) index[selected.id]?.let { "group:$it" } else nodeCode(selected.id)
}

private fun JsonObjectBuilder.nodes(nodes: List<Member>, endpoints: Map<String, NodeInfo>, scrubber: ReportScrubber) {
    putJsonObject("nodes") {
        put("total", nodes.size)
        put("tested", nodes.count { it.latencyMs != null })
        put("failed", nodes.count { it.failed })
        put("localNetwork", nodes.count { it.error == ErrorCode.LocalNetwork })
        put("dnsFailed", nodes.count { it.error == ErrorCode.DnsFailed })
    }
    putJsonArray("nodeList") {
        nodes.take(MAX_LISTED_NODES).forEach { n ->
            addJsonObject {
                put("code", nodeCode(n.id))
                put("subscription", n.subscriptionId?.let(::subscriptionId))
                put("latencyMs", n.latencyMs)
                put("failed", n.failed)
                put("error", n.error?.name)
                endpoints[n.id]?.let(scrubber::endpoint)?.let { put("endpoint", it) }
            }
        }
    }
}

private val NODE_CODE = Regex("n-[0-9a-f]{8}")
private val SUBSCRIPTION_ID = Regex("s[0-9]{1,9}")

/** The node's code (`n-` and 8 hex digits, as the core writes it); null for an id of another shape, which could hold anything. */
private fun nodeCode(id: String): String? = NodeCodes.codeOf(id).takeIf(NODE_CODE::matches)

/** The core's subscription id (`s<n>`); null for any other text. */
private fun subscriptionId(id: String): String? = id.takeIf(SUBSCRIPTION_ID::matches)
