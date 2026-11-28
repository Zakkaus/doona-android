package dev.doona.android.feature.config

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvDiffFile

/**
 * A file of the configuration, as honk lists its sources: the [path] relative to the entry, the
 * file that includes it ([parent]; none for the entry) and how many lines it has.
 */
@Immutable
internal data class RvConfigFile(val path: String, val parent: String?, val lines: Int)

/** What honk runs from the files: DNS request and response rules counted apart, the traffic fallback left out. */
@Immutable
internal data class RvConfigSummary(
    val subscriptions: Int,
    val nodes: Int,
    val groups: Int,
    val trafficRules: Int,
    val dnsUpstreams: Int,
    val dnsRequestRules: Int,
    val dnsResponseRules: Int,
)

/** Why the app does not apply a declaration or a rule: one support record's reasons, as the page names them. */
internal enum class RvSkipReason(@StringRes val label: Int) {
    LinuxOnly(R.string.rv_config_reason_linux),
    ProcessName(R.string.rv_config_reason_process),
    MacAddress(R.string.rv_config_reason_mac),
}

/**
 * One support record the app skips: the declaration or rule as the file spells it at its location ([source], [line]),
 * and every reason on that record.
 */
@Immutable
internal data class RvSkipped(val text: String, val source: String, val line: Int, val reasons: List<RvSkipReason>)

/**
 * The configuration as the overview shows it: the applied revision [rev] and when it was applied, the files, the
 * summary, the records skipped and how many are applied only in part ([constrained]); [nowEpochMs] says which day is today.
 */
@Immutable
internal data class RvConfigState(
    val rev: Int,
    val appliedAtEpochMs: Long,
    val files: List<RvConfigFile>,
    val summary: RvConfigSummary,
    val skipped: List<RvSkipped>,
    val constrained: Int = 0,
    val nowEpochMs: Long,
) {
    /** How many of [skipped] lie in [path]. */
    fun skippedIn(path: String): Int = skipped.count { it.source == path }

    /** The files [path] includes, directly or through another include, in load order: they leave the tree with it. */
    fun includedUnder(path: String): List<String> {
        val under = mutableSetOf(path)
        return files.filter { file -> file.parent in under && under.add(file.path) }.map { it.path }
    }
}

/**
 * A diagnostic from honk's check in one file: its UTF-8 byte span in that file and the group name its message quotes
 * (the text of that span).
 */
@Immutable
internal data class RvConfigDiagnostic(val start: Int, val end: Int, val name: String)

/** A draft of one file in the source editor: the file, its text, the revision the draft is based on, any diagnostic. */
@Immutable
internal data class RvSourceDraft(val path: String, val text: String, val baseRev: Int, val diagnostic: RvConfigDiagnostic? = null)

/**
 * The check's diff before 套用: each changed file against the draft's base [baseRev]; [headRev] is today's head, newer
 * than the base when another apply landed first (the draft is stale and `configApply` answers `conflict`).
 */
@Immutable
internal data class RvConfigDiff(val files: List<RvDiffFile>, val baseRev: Int, val headRev: Int) {
    val stale = headRev != baseRev
}

/** Where an applied revision came from, as honk's db gives its origin. */
internal enum class RvRevisionOrigin(@StringRes val label: Int) {
    Edit(R.string.rv_rule_menu_edit),
    Import(R.string.rv_config_origin_import),
    Template(R.string.rv_policy_template),
    Restore(R.string.rv_config_origin_restore),
}

/**
 * One applied revision: its number, when it was applied, its origin, for a restore the revision it restored, and the
 * [name] the user gave it (null until named; an empty name clears it).
 */
@Immutable
internal data class RvRevision(
    val rev: Int,
    val appliedAtEpochMs: Long,
    val origin: RvRevisionOrigin,
    val restored: Int? = null,
    val name: String? = null,
) {
    fun named(name: String?) = copy(name = name)
}

/** The rename sheet over 版本紀錄: the revision [rev] being named and the [name] typed so far. */
@Immutable
internal data class RvRevisionRename(val rev: Int, val name: String)

/**
 * What 匯入設定檔 shows after the picker: the picked [name]; refused at the budget (`source_limit`, before any parse: no
 * draft, the head unchanged), or staged as a draft whose [files] include one that reaches outside the staged root
 * (`include_escape` on [escapeLine], spelled [escapePath]): the draft stays editable and cannot be applied.
 */
@Immutable
internal data class RvImportState(
    val name: String,
    val headRev: Int,
    val refusedAtLimit: Boolean = false,
    val files: List<RvConfigFile> = emptyList(),
    val escapeLine: Int = 0,
    val escapePath: String = "",
)

/** honk's check budget (`config_validate`): 32 files and 8 MiB, the entry, includes and dependencies together. */
internal object RvConfigLimits {
    const val FILES = 32
    const val SIZE = "8 MiB"
    const val BYTES = 8 * 1024 * 1024
}
