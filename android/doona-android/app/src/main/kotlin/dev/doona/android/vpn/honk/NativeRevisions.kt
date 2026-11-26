package dev.doona.android.vpn.honk

import android.content.Context
import dev.doona.android.R
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.expect
import dev.doona.android.vpn.nativeJson
import dev.doona.android.vpn.read
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * honk's configuration db (`--store db`): every activated configuration is a revision, and a configuration honk rejects
 * adds none, so the revision before the active one is the last configuration that worked. Blocking reads: run on
 * `Dispatchers.IO`.
 */
internal class NativeRevisions(private val api: NativeApi) {
    /** Newest first, without content (`GET /x-honk/config/revisions`). */
    fun revisions(): HonkRevisions = api.read("GET", "/api/v1/x-honk/config/revisions", HonkRevisions.serializer())

    /**
     * Activates [revision] and waits for honk's reload; honk records it as a new revision. A revision honk no longer
     * holds fails with [ErrorCode.NotFound], one it does not accept with [ErrorCode.InvalidArgument].
     */
    suspend fun activate(revision: Long) {
        val accepted = api.expect("POST", "/api/v1/x-honk/config/revisions/$revision/activate", "{}")
        val operation = nativeJson.decodeFromString(OperationAccepted.serializer(), accepted).href
        if (api.finished(operation).status == OperationStatus.Failed) throw EngineException(ErrorCode.InvalidArgument)
    }

    /** Goes back to the newest revision older than the active one; false when there is none. */
    suspend fun restorePrevious(): Boolean {
        val previous = revisions().previous ?: return false
        activate(previous.revision)
        return true
    }

    /**
     * Where honk's last reload failed, read from `GET /runtime`, with the path of the source it names; null when the last
     * reload did not fail. The diagnostics name the accepted sources, which `GET /config` lists.
     */
    fun lastFailure(): ConfigFailure? {
        val reload = api.read("GET", "/api/v1/runtime", HonkRuntimeReload.serializer()).lastReload
        if (reload.status != "failed") return null
        return configFailure(reload.error?.details?.diagnostics.orEmpty(), NativeConfig(api).config().sources)
    }
}

@Serializable
internal class HonkRevisions(
    val active: Long?,
    @SerialName("max_revisions") val maxRevisions: Int,
    val revisions: List<HonkRevision>,
) {
    /** The newest revision older than [active]. */
    val previous: HonkRevision? get() = active?.let { active -> revisions.firstOrNull { it.revision < active } }
}

/** [origin] is `import` (startup imports revision 1), `write` or `activate`; [principal] is who made it. */
@Serializable
internal class HonkRevision(
    val revision: Long,
    val parent: Long?,
    @SerialName("created_at") val createdAt: String,
    val principal: String,
    val origin: String,
    @SerialName("content_sha256") val sha256: String,
    val bytes: Long,
    val sources: List<HonkRevisionSource>,
)

@Serializable
internal class HonkRevisionSource(val path: String, val sha256: String)

/** honk's safe diagnostic: [line] is one-based, null when honk does not know it. */
@Serializable
internal class HonkDiagnostic(
    val level: String,
    @SerialName("source_id") val sourceId: String,
    val line: Long?,
    val code: String,
    val message: String,
)

/** The first error of a configuration honk rejected: the source's [path] and the [line], each null when unknown. */
internal data class ConfigFailure(val path: String?, val line: Long?)

/** The first error-level diagnostic located in [sources]. */
internal fun configFailure(diagnostics: List<HonkDiagnostic>, sources: List<HonkSource>): ConfigFailure {
    val error = diagnostics.firstOrNull { it.level == "error" } ?: return ConfigFailure(null, null)
    return ConfigFailure(sources.firstOrNull { it.id == error.sourceId }?.path, error.line)
}

/** The notification's title and text for a configuration honk cannot use; a tap goes back to the previous revision. */
internal fun Context.configUnusableTitle(): String = getString(R.string.vpn_config_unusable_title)

internal fun Context.configUnusableText(failure: ConfigFailure?): String {
    val path = failure?.path
    val line = failure?.line
    return if (path != null && line != null) {
        getString(R.string.vpn_config_unusable_at, path, line)
    } else {
        getString(R.string.vpn_config_unusable_restore)
    }
}
