package dev.doona.android.engine

/** A file of the tree as `configRead` lists its include manifest: its [path] relative to the entry, the file that includes it. */
data class ConfigFile(val id: String, val path: String, val parentId: String?, val lines: Int)

/** What the tree declares; DNS request and response rules apart, fallbacks left out. */
data class ConfigSummary(
    val subscriptions: Int,
    val nodes: Int,
    val groups: Int,
    val trafficRules: Int,
    val dnsUpstreams: Int,
    val dnsRequestRules: Int,
    val dnsResponseRules: Int,
)

/** A place in a file: honk's 1-based [line] and the UTF-8 byte span `[start, end)` of the text there. */
data class SourceSpan(val fileId: String, val line: Int, val start: Int, val end: Int)

/** Why the app does not apply a declaration or rule, as the core's support report names it. */
enum class SupportReason { LinuxOnly, ProcessName, MacAddress }

/** One support record: where the declaration or rule stands, and every reason; [skipped] false means applied in part. */
data class SupportRecord(val at: SourceSpan, val reasons: List<SupportReason>, val skipped: Boolean = true)

/** `configRead {}`: the head revision, its entry and include manifest in load order, the summary and the support report. */
data class ConfigTree(val rev: Int, val entryId: String, val files: List<ConfigFile>, val summary: ConfigSummary, val support: List<SupportRecord>)

/** Which bytes [Engine.configSource] reads: an applied revision's, or a draft's at its current version. */
sealed interface SourceAt {
    data class Revision(val rev: Int) : SourceAt
    data class Draft(val draft: ConfigDraft) : SourceAt
}

/** Where a draft starts: today's head, an applied revision (to restore it), or a picked tree the picker staged. */
sealed interface DraftFrom {
    data object Head : DraftFrom
    data class Revision(val rev: Int) : DraftFrom
    data class Import(val token: String) : DraftFrom
}

/** A private working tree: its [id], its edit counter [version] and the revision it is based on. */
data class ConfigDraft(val id: String, val version: Int, val baseRev: Int)

/** An edit of one file of a draft. */
sealed interface ConfigEditOp {
    /** The editor's whole buffer for the file. */
    data class ReplaceText(val text: String) : ConfigEditOp
}

/** A diagnostic code `configCheck` gives at a place. */
enum class DiagnosticCode { UnknownGroup, IncludeEscape }

/** One diagnostic of a check: its code and where it stands; null when honk gives no location. */
data class ConfigDiagnostic(val code: DiagnosticCode, val at: SourceSpan?)

/** How many lines of a file a draft adds and removes against its base. */
data class ConfigFileChange(val fileId: String, val added: Int, val removed: Int)

/**
 * `configCheck` on a draft's [version]: whether it parsed and validated, its [diagnostics], support report and changed
 * files against its base; [files] is the draft's manifest. The editor reads both sides' bytes for the line diff.
 */
data class ConfigCheck(
    val draft: ConfigDraft,
    val valid: Boolean,
    val diagnostics: List<ConfigDiagnostic>,
    val support: List<SupportRecord>,
    val changes: List<ConfigFileChange>,
    val files: List<ConfigFile>,
)

/** `configApply`'s result: the new head and the runtime generation prepared from it. */
data class ConfigApplied(val rev: Int, val generation: Int)

/** Where an applied revision came from. */
enum class RevisionOrigin { Edit, Import, Template, Restore }

/** One applied revision, newest first in `configHistory`: [restored] for a restore, [name] the user's, null until named. */
data class ConfigRevision(val rev: Int, val appliedAtEpochMs: Long, val origin: RevisionOrigin, val restored: Int? = null, val name: String? = null)

/** What an export holds: every byte as applied, or a tree that loads elsewhere. */
enum class ExportKind { Archive, Portable }

/** A staged export: the private [token] Kotlin streams to the chosen destination, its file count and whether it is portable. */
data class ConfigExport(val token: String, val files: Int, val portable: Boolean)

/**
 * The configuration commands of the core (config-plan.md "JNI contract", schema 4): the honk source tree, drafts bound to
 * a base revision, checks, apply, history and export. `rev` names immutable source bytes; a draft is inert until
 * [Engine.configApply]. [Engine] carries them. Files are named by their source ID ([ConfigFile.id]), never by a filesystem path.
 */

interface ConfigEngine {
    /** `configRead {}`: the applied head's tree, summary and support report. */
    suspend fun configRead(): Result<ConfigTree>

    /** The exact UTF-8 text of file [fileId] at [at]; [ErrorCode.NotFound] for a file that tree does not hold. */
    suspend fun configSource(at: SourceAt, fileId: String): Result<String>

    /** A new draft [from] the head, a revision or a staged import; [ErrorCode.SourceLimit] when an import passes the budget. */
    suspend fun configDraft(from: DraftFrom): Result<ConfigDraft>

    /** Applies [op] to file [fileId] of [draft]; the result is the draft's next version. [ErrorCode.Conflict] for a stale version. */
    suspend fun configEdit(draft: ConfigDraft, fileId: String, op: ConfigEditOp): Result<ConfigDraft>

    /** Checks [draft] at its version: diagnostics, support report and the files it changes against its base. */
    suspend fun configCheck(draft: ConfigDraft): Result<ConfigCheck>

    /** Makes [draft] the new head; [ErrorCode.Conflict] when its base is no longer the head or its version is stale. */
    suspend fun configApply(draft: ConfigDraft): Result<ConfigApplied>

    /** The applied revisions, newest first. */
    suspend fun configHistory(): Result<List<ConfigRevision>>

    /** Names revision [rev]; a null [name] takes its name away. Changes no source byte. */
    suspend fun configName(rev: Int, name: String?): Result<Unit>

    /** Stages revision [rev] for export as [kind]; the caller streams the token to the destination the user picked. */
    suspend fun configExport(rev: Int, kind: ExportKind): Result<ConfigExport>
}
