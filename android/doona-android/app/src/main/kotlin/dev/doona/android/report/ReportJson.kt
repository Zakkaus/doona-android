package dev.doona.android.report

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.doona.android.engine.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

/** What the report reads besides the core's diagnostics: the mode and per-app choice the app keeps, and honk ([HonkView]). */
internal data class EngineView(val settings: Settings, val honk: HonkView)

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
        put("core", honkCore(engine.honk.version) ?: JsonNull)
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
        put("settings", settingsSummary(engine))
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

/** The settings by counts and kinds: honk's state and catalog, the mode and per-app choice. */
private fun settingsSummary(engine: EngineView): JsonObject = buildJsonObject {
    put("runtime", honkRuntime(engine.honk.runtime) ?: JsonNull)
    put("mode", engine.settings.mode.name)
    putJsonObject("perApp") {
        put("policy", engine.settings.perApp.policy.name)
        put("apps", engine.settings.perApp.packages.size)
    }
    put("catalog", engine.honk.catalog?.let(::honkCatalog) ?: JsonNull)
}
