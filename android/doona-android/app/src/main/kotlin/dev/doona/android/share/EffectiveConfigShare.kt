package dev.doona.android.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * The key of `ClipDescription.EXTRA_IS_SENSITIVE`, which Android 13 added: it asks the system not to show the clip in
 * its copy preview. Below 13 (minSdk is 31) nothing reads the extra, so it is set on every version rather than guarded.
 */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/**
 * The clip for copying the effective config [text] ([dev.doona.android.engine.EffectiveConfig.text]) under [label]:
 * plain text, marked sensitive because it describes the user's routing even though it names no credential (ARCH D21).
 */
fun effectiveConfigClip(label: CharSequence, text: String): ClipData {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    return clip
}

/**
 * The share sheet, titled [title], for the effective config [text]: `ACTION_SEND` of `text/plain` with the text as
 * `EXTRA_TEXT` and [title] as the subject. The text is cut at 1 MiB by the core and carries no file, so no
 * FileProvider is involved; start it from an Activity context.
 */
fun effectiveConfigShareIntent(title: String, text: String): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType(ClipDescription.MIMETYPE_TEXT_PLAIN)
        .putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, title)
    return Intent.createChooser(send, title)
}

/** What the effective config page's 複製 and 匯出 call. */
class EffectiveConfigActions(val copy: () -> Unit, val export: () -> Unit)

/**
 * The page's two actions on the config text [text] (null while it is not read: both do nothing), titled [title] on the
 * clipboard and the share sheet. The page lane passes `Engine.effectiveConfig()`'s text.
 */
@Composable
fun rememberEffectiveConfigActions(title: String, text: String?): EffectiveConfigActions {
    val clipboard = LocalClipboard.current
    val context: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(text)
    return EffectiveConfigActions(
        copy = { current?.let { scope.launch { clipboard.setClipEntry(ClipEntry(effectiveConfigClip(title, it))) } } },
        export = { current?.let { context.startActivity(effectiveConfigShareIntent(title, it)) } },
    )
}
