package dev.doona.android.report

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.service.chooser.ChooserResult
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import dev.doona.android.app.DoonaApplication
import java.io.File

/** The report's media type. */
const val REPORT_MIME = "application/zip"

/** The FileProvider in the manifest that serves the report zips from `cacheDir/reports`. */
fun reportAuthority(context: Context) = "${context.packageName}.reports"

/** The directory the export writes into; `res/xml/report_paths.xml` names the same one. */
fun reportDir(context: Context) = File(context.cacheDir, "reports")

/**
 * The share sheet for the report [file], titled [title]: `ACTION_SEND` of the zip through this app's FileProvider. The
 * app the user picks gets read access to that one file and nothing else; the content URI carries no path of the app's.
 * The sheet tells [ReportPickReceiver] of a pick, with the export's [token]. It resolves the file's path, so call it off
 * the main thread.
 */
fun reportShareIntent(context: Context, file: File, title: String, token: String): Intent {
    val uri = FileProvider.getUriForFile(context, reportAuthority(context), file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(REPORT_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The share sheet reads the grant from the clip, and passes it to the app picked.
    send.clipData = ClipData.newRawUri(file.name, uri)
    return Intent.createChooser(send, title, pickSender(context, token))
}

/**
 * To [ReportPickReceiver], with the export's [token] as the intent's identifier: a PendingIntent of its own for each
 * export, which no later export's replaces. Mutable, so the sheet can add the app picked; explicit, so no other app can
 * receive it.
 */
private fun pickSender(context: Context, token: String): IntentSender {
    val picked = Intent(context, ReportPickReceiver::class.java).setIdentifier(token)
    return PendingIntent.getBroadcast(context, 0, picked, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
}

/**
 * Whether the share sheet's callback [intent] reports that the user picked an app: it names the component picked, or
 * from Android 15 its result does. The sheet also calls back for its own actions, such as Copy, which send the zip
 * nowhere and name no app.
 */
fun isPick(intent: Intent): Boolean =
    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java) != null ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && pickedByResult(intent))

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun pickedByResult(intent: Intent): Boolean {
    val result = intent.getParcelableExtra(Intent.EXTRA_CHOOSER_RESULT, ChooserResult::class.java) ?: return false
    return result.type == ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT && result.selectedComponent != null
}

/**
 * Hears a report's share sheet call back ([reportShareIntent]). Not exported, so only this app's own PendingIntent
 * reaches it; declared in the manifest, so a pick that comes after the process ended starts it again and still clears
 * the crash files its export holds ([ReportExport.picked]).
 */
class ReportPickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.identifier ?: return
        if (!isPick(intent)) return
        val reports = (context.applicationContext as DoonaApplication).container.reports
        val pending = goAsync()
        reports.picked(token) { pending.finish() }
    }
}
