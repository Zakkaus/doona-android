package dev.doona.android.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
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
    return Intent.createChooser(send, title)
}
