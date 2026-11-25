package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme

/**
 * An entry of the licences page: its [name], its licence (a component's SPDX expression), and its text, the asset
 * [file] of a component doona ships or, for an entry doona words itself in each language ([rvBrandMarks]), the string
 * [notice].
 */
@Immutable
internal data class RvLicense(val name: String, val license: String, val file: String? = null, @StringRes val notice: Int? = null) {
    init {
        require((file == null) != (notice == null)) { "$name: a file or a notice" }
    }
}

/**
 * The sample's components, by name: the app's and the core's dependencies from the build files and the fonts and
 * glyphs it carries, each with its licence as `cargo metadata` or the vendored file states it. The texts are the files
 * themselves: the app's `assets/licenses`, and the debug assets for tokio, honk and BoringSSL. Apache-2.0 is the one
 * text the app already carries for Material Symbols; serde, under MIT or Apache-2.0, opens it.
 */
internal object RvLicenses {
    private const val APACHE = "licenses/material-symbols-Apache-2.0.txt"

    val all = listOf(
        RvLicense("AndroidX", "Apache-2.0", APACHE),
        RvLicense("BoringSSL", "Apache-2.0", "licenses/boringssl-LICENSE.txt"),
        RvLicense("Bootstrap Icons", "MIT", "licenses/bootstrap-icons-MIT.txt"),
        RvLicense("circle-flags", "MIT", "licenses/circle-flags-MIT.txt"),
        RvLicense("honk", "GPL-3.0-only", "licenses/honk-GPL-3.0.txt"),
        RvLicense("Inter", "OFL-1.1", "licenses/inter-OFL.txt"),
        RvLicense("Jetpack Compose", "Apache-2.0", APACHE),
        RvLicense("Kotlin", "Apache-2.0", APACHE),
        RvLicense("kotlinx.coroutines", "Apache-2.0", APACHE),
        RvLicense("kotlinx.serialization", "Apache-2.0", APACHE),
        RvLicense("Material Symbols", "Apache-2.0", APACHE),
        RvLicense("Noto Sans TC", "OFL-1.1", "licenses/noto-sans-tc-OFL.txt"),
        RvLicense("serde", "MIT OR Apache-2.0", APACHE),
        RvLicense("tokio", "MIT", "licenses/tokio-MIT.txt"),
    )
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
internal fun rvLicenseEntries(): List<RvLicense> = RvLicenses.all + rvBrandMarks()

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
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
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
    val paragraphs = remember(license.file, notice) { rvParagraphs(notice ?: assets.open(requireNotNull(license.file)).bufferedReader().use { it.readText() }) }
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

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvLicensesScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvLicensesScreen(onOpen = {}, onBack = {}) }
}
