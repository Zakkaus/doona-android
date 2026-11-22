package dev.doona.android.data.apps

import android.content.Context
import android.content.Intent
import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.InstalledApps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * The launchable apps from [android.content.pm.PackageManager]. Seeing them needs the `<queries>` entry for the launcher
 * intent in the manifest, which is why the list is of launchable apps and not of every package (ARCH D16).
 */
class PackageInstalledApps(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : InstalledApps {
    private val context = context.applicationContext

    override suspend fun launchable(): List<InstalledApp> = withContext(io) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance()
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { (pkg, _) -> pkg }
            .map { (pkg, label) -> InstalledApp(pkg, label.ifBlank { pkg }) }
            .sortedWith(compareBy<InstalledApp, String>(collator) { it.label }.thenBy { it.packageName })
    }
}
