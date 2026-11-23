package dev.doona.android.prototype.rv.config

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RV_NOW

/** Where an applied revision came from, as `configHistory` gives its origin. */
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
internal class RvRevision(
    val rev: Int,
    val appliedAtEpochMs: Long,
    val origin: RvRevisionOrigin,
    val restored: Int? = null,
    val name: String? = null,
) {
    fun named(name: String?) = RvRevision(rev, appliedAtEpochMs, origin, restored, name)
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
internal class RvImportState(
    val name: String,
    val headRev: Int,
    val refusedAtLimit: Boolean = false,
    val files: List<RvConfigFile> = emptyList(),
    val escapeLine: Int = 0,
    val escapePath: String = "",
)

internal object RvConfigHistoryData {
    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR

    /** The revisions the sample names. */
    private const val HEAD = 9
    private const val HOME = 7
    private const val ROUTER = 6
    private const val FIRST = 1

    /** The applied revisions, newest first; the app keeps the latest ten, and there are nine. */
    val revisions = listOf(
        RvRevision(9, RvConfigData.APPLIED_AT, RvRevisionOrigin.Edit),
        RvRevision(8, RV_NOW - DAY - 13 * HOUR, RvRevisionOrigin.Restore, restored = 6),
        RvRevision(7, RV_NOW - DAY - 15 * HOUR, RvRevisionOrigin.Edit),
        RvRevision(6, RV_NOW - 3 * DAY - 2 * HOUR, RvRevisionOrigin.Import),
        RvRevision(5, RV_NOW - 5 * DAY - 4 * HOUR, RvRevisionOrigin.Edit),
        RvRevision(4, RV_NOW - 6 * DAY - 1 * HOUR, RvRevisionOrigin.Edit),
        RvRevision(3, RV_NOW - 9 * DAY - 7 * HOUR, RvRevisionOrigin.Import),
        RvRevision(2, RV_NOW - 12 * DAY - 3 * HOUR, RvRevisionOrigin.Edit),
        RvRevision(1, RV_NOW - 12 * DAY - 5 * HOUR, RvRevisionOrigin.Template),
    )

    /**
     * The same revisions after the user named four of them: the head with a long name (it wraps at large font sizes),
     * a short one, the import and the first template; the rest show their time as before.
     */
    val named = revisions.map {
        it.named(
            when (it.rev) {
                HEAD -> "東京出差：影音與 AI 走日本節點"
                HOME -> "家用"
                ROUTER -> "路由器匯入"
                FIRST -> "初次設定"
                else -> null
            },
        )
    }

    /** Naming revision 8, which has no name yet: the field is empty and shows what the row says without one. */
    val rename = RvRevisionRename(8, "")

    /** The pin's `SourceLimits::DEFAULT` (config-plan.md:36): 32 files, 8 MiB, entry, includes and companions together. */
    const val LIMIT_FILES = 32
    const val LIMIT_SIZE = "8 MiB"

    private const val PICKED = "router.dae"

    /** The picked tree passed the 32-file budget: refused before parse, revision 9 untouched. */
    val limit = RvImportState(PICKED, RvConfigData.REV, refusedAtLimit = true)

    /** The picked entry includes a glob outside its own directory; its relative include staged fine. */
    val escape = RvImportState(
        PICKED,
        RvConfigData.REV,
        files = listOf(RvConfigFile(PICKED, null, 40), RvConfigFile(RvConfigData.NODES, PICKED, 12)),
        escapeLine = 4,
        escapePath = "/etc/dae/rules.d/*.dae",
    )
}
