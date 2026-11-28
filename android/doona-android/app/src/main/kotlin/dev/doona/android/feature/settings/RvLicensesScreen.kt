package dev.doona.android.feature.settings

import android.content.res.AssetManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import org.json.JSONObject
import java.io.DataInputStream

/** A component or family in the release index, or the localized brand notice. */
@Immutable
internal data class RvLicense(
    val name: String,
    val license: String,
    private val entries: List<JSONObject> = emptyList(),
    private val ranges: JSONObject? = null,
    @StringRes val notice: Int? = null,
) {
    fun text(assets: AssetManager): String {
        val paragraphs = mutableListOf<String>()
        val texts = mutableMapOf<String, String>()
        for (entry in entries) {
            val id = entry.getString("name")
            paragraphs.add("$id: ${entry.getString("license")}")
            val attribution = entry.getJSONArray("attribution")
            for (j in 0 until attribution.length()) paragraphs.add(attribution.getString(j))
            entry.getString("notice").takeIf(String::isNotBlank)?.let(paragraphs::add)
            val bodies = entry.getJSONArray("bodies")
            for (j in 0 until bodies.length()) texts[bodies.getString(j)] = bodies.getString(j)
            val extras = entry.getJSONArray("extras")
            for (j in 0 until extras.length()) {
                val extra = extras.getJSONObject(j)
                val label = texts.getOrPut(extra.getString("text")) { "$id: ${extra.getString("file")}" }
                paragraphs.add(label)
            }
        }
        val spans = texts.map { (key, label) -> label to checkNotNull(ranges).getJSONArray(key) }.sortedBy { it.second.getInt(0) }
        DataInputStream(assets.open("licenses/notices.txt")).use { input ->
            var position = 0
            for ((label, span) in spans) {
                val skip = span.getInt(0) - position
                check(input.skipBytes(skip) == skip)
                paragraphs.add(label)
                paragraphs.add(ByteArray(span.getInt(1)).also(input::readFully).toString(Charsets.UTF_8))
                position = span.getInt(0) + span.getInt(1)
            }
        }
        return paragraphs.joinToString("\n\n")
    }
}

internal object RvLicenses {
    fun all(assets: AssetManager): List<RvLicense> {
        val index = JSONObject(assets.open("licenses/index.json").bufferedReader().use { it.readText() })
        val components = index.getJSONArray("components")
        val entries = List(components.length(), components::getJSONObject)
        return mapOf(
            "AndroidX" to "androidx.*",
            "BoringSSL" to "BoringSSL",
            "Bootstrap Icons" to "Bootstrap Icons brand marks",
            "circle-flags" to "circle-flags",
            "honk" to "honk-*",
            "Inter" to "Inter",
            "Jetpack Compose" to "androidx.compose.*",
            "Kotlin" to "org.jetbrains.kotlin:kotlin-stdlib",
            "kotlinx.coroutines" to "org.jetbrains.kotlinx:kotlinx-coroutines-*",
            "kotlinx.serialization" to "org.jetbrains.kotlinx:kotlinx-serialization-*",
            "Material Symbols" to "Material Symbols Rounded",
            "Noto Sans TC" to "Noto Sans TC",
            "serde" to "serde",
            "tokio" to "tokio",
        ).map { (name, pattern) ->
            val matches = entries.filter {
                val id = it.getString("name")
                if (pattern.endsWith('*')) id.startsWith(pattern.dropLast(1)) else id == pattern
            }
            RvLicense(name, matches.map { it.getString("license") }.distinct().joinToString(" / "), matches, index.getJSONObject("ranges"))
        }
    }
}

/**
 * The last entry: the brand marks of 新增服務規則, which are no component but marks used under their sources' terms
 * (Simple Icons CC0, Wikimedia Commons public domain, Bootstrap Icons MIT), with the trademark notice of
 * `app/src/main/BRAND_ICONS.md`.
 */
@Composable
internal fun rvBrandMarks(): RvLicense = RvLicense(
    stringResource(R.string.rv_license_brand_marks),
    stringResource(R.string.rv_license_brand_marks_licenses),
    notice = R.string.rv_license_brand_marks_text,
)

/** The page's entries in its order: the components, then the brand marks. [RvLicensesScreen]'s `onOpen` indexes it. */
@Composable
internal fun rvLicenseEntries(): List<RvLicense> {
    val assets = LocalContext.current.assets
    return remember(assets) { RvLicenses.all(assets) } + rvBrandMarks()
}

/**
 * 開放原始碼授權, a page of 設定's 關於, in DNS's language: one card of the components, each its name over its licence with no
 * glyph, as DNS's 自訂 row, and the brand marks last; a row opens its text ([onOpen], an index of [rvLicenseEntries]).
 */
@Composable
internal fun RvLicensesScreen(onOpen: (Int) -> Unit, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_licenses)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvSettingsColumn {
            val entries = rvLicenseEntries()
            RvSettingsCard { entries.forEachIndexed { i, it -> RvSettingsNavRow(it.name, null, { onOpen(i) }, it.license, titleKeepWords = false, subtitleKeepWords = false) } }
        }
    }
}

/** An entry's text under its name as the large title, a paragraph at a time in the body's 17 sp, in ink. */
@Composable
internal fun RvLicenseText(license: RvLicense, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val assets = LocalContext.current.assets
    val notice = license.notice?.let { stringResource(it) }
    val paragraphs = remember(license, notice) { rvParagraphs(notice ?: license.text(assets)) }
    RvScrollPage(license.name, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(license.name, titleBlock, insets)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), verticalArrangement = Arrangement.spacedBy(RvSpace.controlStack)) {
            paragraphs.forEach { RvText(it, Rv.type.rowSub, maxLines = Int.MAX_VALUE) }
        }
    }
}

/**
 * A licence file's text as paragraphs for a phone's width: blank lines part paragraphs; inside one, the file's lines
 * wrapped at 80 columns join with a space, but a rule (`-----`), a line after one, and a list item (`*`, `-`, `(a)`,
 * `1.`) keep their own line. Indentation goes; the words stay as the file has them.
 */
internal fun rvParagraphs(text: String): List<String> = text.replace("\r\n", "\n").split(BLANK_LINE).mapNotNull { block ->
    val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return@mapNotNull null
    buildString {
        lines.forEachIndexed { i, line ->
            if (i > 0) append(if (line.isRule() || lines[i - 1].isRule() || LIST_ITEM.containsMatchIn(line)) "\n" else " ")
            append(line)
        }
    }
}

private fun String.isRule() = all { it == '-' || it == '=' || it == '*' || it == '_' }

private val BLANK_LINE = Regex("\n[ \t]*\n")
private val LIST_ITEM = Regex("""^([*•-] |\([a-z0-9]+\) |[0-9]+\. |[a-z]\) )""")
