package dev.doona.android.feature.config

import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.designsystem.rv.RvDiffKind.Added
import dev.doona.android.designsystem.rv.RvDiffKind.Removed
import dev.doona.android.designsystem.rv.RvDiffKind.Same
import dev.doona.android.designsystem.rv.RvDiffLine

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
    val source = RvSourceDraft(CN, clean, RvConfigData.REV.toLong())

    /** The draft after `configCheck` refused the unknown group on line 7. */
    val error = RvSourceDraft(CN, broken, RvConfigData.REV.toLong(), broken.indexOf(MISSING).let { at -> RvConfigDiagnostic(bytes(broken, at), bytes(broken, at) + MISSING.length, MISSING) })

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

    val diff = RvConfigDiff(files, RvConfigData.REV.toLong(), RvConfigData.REV.toLong())

    /** The same diff after another apply made revision 10 the head. */
    val stale = RvConfigDiff(files, RvConfigData.REV.toLong(), RvConfigData.REV + 1L)
}
