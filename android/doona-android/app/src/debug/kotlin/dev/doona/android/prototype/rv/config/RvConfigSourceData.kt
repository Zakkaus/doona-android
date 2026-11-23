package dev.doona.android.prototype.rv.config

import androidx.compose.runtime.Immutable
import dev.doona.android.prototype.rv.RvDiffFile
import dev.doona.android.prototype.rv.RvDiffKind.Added
import dev.doona.android.prototype.rv.RvDiffKind.Removed
import dev.doona.android.prototype.rv.RvDiffKind.Same
import dev.doona.android.prototype.rv.RvDiffLine

/**
 * A diagnostic from `configCheck` in one file: its UTF-8 byte span in that file and the group name its message quotes.
 * The contract gives `line` and `column` (config-plan.md:104); the sample spans the word at that column.
 */
@Immutable
internal class RvConfigDiagnostic(val start: Int, val end: Int, val name: String)

/** A draft of one file in the source editor: the file, its text, the revision the draft is based on, any diagnostic. */
@Immutable
internal class RvSourceDraft(val path: String, val text: String, val baseRev: Int, val diagnostic: RvConfigDiagnostic? = null)

/**
 * The check's diff before 套用: each changed file against the draft's base [baseRev]; [headRev] is today's head, newer
 * than the base when another apply landed first (the draft is stale and `configApply` answers `conflict`).
 */
@Immutable
internal class RvConfigDiff(val files: List<RvDiffFile>, val baseRev: Int, val headRev: Int) {
    val stale = headRev != baseRev
}

/**
 * The sample's `rules/cn.dae`, the include the editor opens. Revision 9 sends 影音 to `proxy`; the draft sends it to
 * `media` (first mistyped as an undeclared group, after the multibyte `影音`) and adds line 13.
 */
internal object RvConfigSourceData {
    const val CN = "rules/cn.dae"
    private const val MISSING = "stream"

    private fun cn(target: String, wiki: Boolean) = listOfNotNull(
        "# 中國大陸直連",
        "routing {",
        "  domain(geosite:cn) -> direct",
        "  dip(geoip:cn) -> direct",
        "  domain(suffix: cn.example) -> direct",
        "",
        "  domain(keyword: 影音) -> $target",
        "  domain(suffix: video.example) -> media",
        "  domain(suffix: music.example) -> media",
        "",
        "  # 公司內網",
        "  domain(suffix: corp.example) -> direct",
        "  domain(suffix: wiki.corp.example) -> direct".takeIf { wiki },
        "  dip(10.8.0.0/16) -> direct",
        "",
        "  # 下載",
        "  domain(suffix: mirror.example) -> direct",
        "  domain(suffix: cdn.example) && l4proto(tcp) -> direct",
        "",
        "  domain(suffix: chat.example) -> proxy",
        "  domain(suffix: news.example) -> proxy",
        "}",
    ).joinToString("\n")

    val clean = cn("media", wiki = true)
    val broken = cn(MISSING, wiki = true)

    /** The draft after its edits, checked clean. */
    val source = RvSourceDraft(CN, clean, RvConfigData.REV)

    /** The draft after `configCheck` refused the unknown group on line 7. */
    val error = RvSourceDraft(CN, broken, RvConfigData.REV, broken.indexOf(MISSING).let { at -> RvConfigDiagnostic(bytes(broken, at), bytes(broken, at) + MISSING.length, MISSING) })

    private fun bytes(text: String, index: Int) = text.substring(0, index).encodeToByteArray().size

    /** The check's diff against revision 9: 影音's group and one added rule in `rules/cn.dae`, a group's policy in the entry. */
    private val files = listOf(
        RvDiffFile(
            RvConfigData.ENTRY,
            listOf(
                listOf(
                    RvDiffLine(Same, 24, "  media {"),
                    RvDiffLine(Same, 25, "    filter: name(keyword: 'HK')"),
                    RvDiffLine(Removed, 26, "    policy: min"),
                    RvDiffLine(Added, 26, "    policy: min_moving_avg"),
                    RvDiffLine(Same, 27, "  }"),
                ),
            ),
        ),
        RvDiffFile(
            CN,
            listOf(
                listOf(
                    RvDiffLine(Same, 6, ""),
                    RvDiffLine(Removed, 7, "  domain(keyword: 影音) -> proxy"),
                    RvDiffLine(Added, 7, "  domain(keyword: 影音) -> media"),
                    RvDiffLine(Same, 8, "  domain(suffix: video.example) -> media"),
                ),
                listOf(
                    RvDiffLine(Same, 12, "  domain(suffix: corp.example) -> direct"),
                    RvDiffLine(Added, 13, "  domain(suffix: wiki.corp.example) -> direct"),
                    RvDiffLine(Same, 14, "  dip(10.8.0.0/16) -> direct"),
                ),
            ),
        ),
    )

    val diff = RvConfigDiff(files, RvConfigData.REV, RvConfigData.REV)

    /** The same diff after another apply made revision 10 the head. */
    val stale = RvConfigDiff(files, RvConfigData.REV, RvConfigData.REV + 1)
}
