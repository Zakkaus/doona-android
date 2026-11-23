package dev.doona.android.report

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File

/** The report's media type. */
const val REPORT_MIME = "application/zip"

/** The FileProvider in the manifest that serves the report zips from `cacheDir/reports`. */
fun reportAuthority(context: Context) = "${context.packageName}.reports"

/** The directory the export writes into; `res/xml/report_paths.xml` names the same one. */
fun reportDir(context: Context) = File(context.cacheDir, "reports")

/** What the share sheet sends this app when the user picks an app for the report ([reportPicks]). */
private const val ACTION_PICKED = "dev.doona.android.action.REPORT_PICKED"

/**
 * Calls [onPicked] whenever the user picks an app in a report's share sheet, for as long as the process lives. The sheet
 * names the app picked; it calls back without one for its own actions, such as Copy, which send the zip nowhere. Only
 * this app can send the broadcast: the receiver is not exported, and the sheet sends it through this app's own
 * [PendingIntent].
 */
fun reportPicks(context: Context, onPicked: () -> Unit) {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java) != null) onPicked()
        }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PICKED), ContextCompat.RECEIVER_NOT_EXPORTED)
}

/**
 * The share sheet for the report [file], titled [title]: `ACTION_SEND` of the zip through this app's FileProvider. The
 * app the user picks gets read access to that one file and nothing else; the content URI carries no path of the app's.
 * The sheet tells this app of a pick ([reportPicks]). It resolves the file's path, so call it off the main thread.
 */
fun reportShareIntent(context: Context, file: File, title: String): Intent {
    val uri = FileProvider.getUriForFile(context, reportAuthority(context), file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(REPORT_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The share sheet reads the grant from the clip, and passes it to the app picked.
    send.clipData = ClipData.newRawUri(file.name, uri)
    return Intent.createChooser(send, title, pickSender(context))
}

/** Mutable, so the sheet can add the app picked; the package makes it explicit, so no other app can receive it. */
private fun pickSender(context: Context): IntentSender {
    val picked = Intent(ACTION_PICKED).setPackage(context.packageName)
    return PendingIntent.getBroadcast(context, 0, picked, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
}
