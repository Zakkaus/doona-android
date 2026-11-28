package dev.doona.android.prototype.rv.gallery

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCodeText
import dev.doona.android.designsystem.rv.RvDiffAt
import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.designsystem.rv.RvDiffKind.Added
import dev.doona.android.designsystem.rv.RvDiffKind.Removed
import dev.doona.android.designsystem.rv.RvDiffKind.Same
import dev.doona.android.designsystem.rv.RvDiffLine
import dev.doona.android.designsystem.rv.RvSourceDiff
import dev.doona.android.designsystem.rv.RvSourceField
import dev.doona.android.designsystem.rv.RvSourceMark
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.rvSourceStyle

/**
 * Code text ([RvCodeText]) on a card: a short file with every role doona colours, a comment, keywords (a section, where
 * a rule sends, `fallback`), strings, numbers, calls, keys, the operator and punctuation.
 */
@Composable
internal fun RvGalleryCode() {
    RvCard { RvCodeText(RV_GALLERY_CODE, rvSourceStyle(), Modifier.padding(RvSpace.rowPadding)) }
}

/**
 * The source field holding a small rules file, with one mark after a multibyte word: a group the file does not declare.
 * It opens with the cursor on the mark, as after a tap there, so its message shows under the card.
 */
@Composable
internal fun RvGallerySource() {
    var value by remember { mutableStateOf(TextFieldValue(RV_GALLERY_SOURCE, TextRange(RV_GALLERY_SOURCE.indexOf(MISSING)))) }
    val mark = RvSourceMark(rvByteOf(RV_GALLERY_SOURCE, MISSING), rvByteOf(RV_GALLERY_SOURCE, MISSING) + MISSING.length, stringResource(R.string.rv_config_unknown_group, MISSING))
    RvSourceField(value, { value = it }, RV_GALLERY_FILE, marks = listOf(mark))
}

/** The same file's diff after the fix: the line that named the missing group, and a rule added further down. */
@Composable
internal fun RvGalleryDiff(onOpen: (RvDiffAt) -> Unit = {}) {
    RvSourceDiff(RV_GALLERY_DIFF, onOpen)
}

/** The UTF-8 byte offset in [text] of the first [word]: what honk reports, counted from the file's start. */
internal fun rvByteOf(text: String, word: String): Int = text.substring(0, text.indexOf(word)).encodeToByteArray().size

private const val MISSING = "stream"
private const val RV_GALLERY_FILE = "rules/media.dae"

internal val RV_GALLERY_SOURCE = """
    # 影音服務
    routing {
      domain(keyword: 影音) -> $MISSING
      domain(suffix: video.example) -> media
      fallback: proxy
    }
""".trimIndent()

internal val RV_GALLERY_CODE = """
    # 香港節點
    group {
      hk {
        filter: name(regex: '港|HK')
        policy: min_avg10
      }
    }
    routing {
      !pname(helper) && dport(443) -> block
      dip(10.0.0.0/8) -> direct
      fallback: hk
    }
""".trimIndent()

internal val RV_GALLERY_DIFF = RvDiffFile(
    RV_GALLERY_FILE,
    listOf(
        listOf(
            RvDiffLine(Same, 2, "routing {"),
            RvDiffLine(Removed, 3, "  domain(keyword: 影音) -> $MISSING"),
            RvDiffLine(Added, 3, "  domain(keyword: 影音) -> media"),
            RvDiffLine(Same, 4, "  domain(suffix: video.example) -> media"),
        ),
        listOf(
            RvDiffLine(Same, 11, "  domain(suffix: music.example) -> media"),
            RvDiffLine(Added, 12, "  domain(suffix: radio.example) -> media"),
            RvDiffLine(Same, 13, "  fallback: proxy"),
        ),
    ),
)
