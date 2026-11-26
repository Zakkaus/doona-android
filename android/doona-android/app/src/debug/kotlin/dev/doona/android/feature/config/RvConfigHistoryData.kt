package dev.doona.android.feature.config

import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.designsystem.rv.RvDiffKind.Added
import dev.doona.android.designsystem.rv.RvDiffKind.Removed
import dev.doona.android.designsystem.rv.RvDiffKind.Same
import dev.doona.android.designsystem.rv.RvDiffLine
import dev.doona.android.prototype.rv.data.RV_NOW

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

    /**
     * What restoring revision 8 (or 7) changes against the head: those sent `ai` through the US nodes, revision 9
     * through Japan's (its name says so).
     */
    val restoreDiff = RvDiffFile(
        RvConfigData.ENTRY,
        listOf(
            listOf(
                RvDiffLine(Same, 28, "  ai {"),
                RvDiffLine(Removed, 29, "    filter: name(keyword: 'JP')"),
                RvDiffLine(Added, 29, "    filter: name(keyword: 'US')"),
                RvDiffLine(Same, 30, "    policy: min_moving_avg"),
                RvDiffLine(Same, 31, "  }"),
            ),
        ),
    )

    private const val PICKED = "router.dae"

    /** The picked tree passed the 32-file budget: refused before parse, revision 9 untouched. */
    val limit = RvImportState(PICKED, RvConfigData.REV, refusedAtLimit = true)

    /** The picked entry includes a glob outside its own directory; its relative include staged fine. */
    val escape = RvImportState(
        PICKED,
        RvConfigData.REV,
        files = listOf(RvConfigFile(PICKED, null, 40), RvConfigFile(RvConfigData.NODES, PICKED, 12)),
        escapeLine = 4,
        escapePath = "/etc/honk/rules.d/*.dae",
    )
}
