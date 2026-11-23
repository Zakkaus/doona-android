package dev.doona.android.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.InstalledApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class PackageInstalledAppsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /** A launcher activity of [pkg] with the label the launcher shows; Robolectric starts every test with none. */
    private fun launcherActivity(pkg: String, label: String, activity: String = "$pkg.Main") {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = activity
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            }
            nonLocalizedLabel = label
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(launcher, info)
    }

    private fun launchable() = runBlocking { PackageInstalledApps(context).launchable() }

    @Test
    fun listsLaunchableAppsSortedByLabelWithoutDoona() {
        launcherActivity("com.example.zebra", "Zebra")
        launcherActivity(context.packageName, "doona")
        launcherActivity("com.example.browser", "Browser")
        launcherActivity("com.example.apple", "apple")

        assertEquals(
            listOf(
                InstalledApp("com.example.apple", "apple"),
                InstalledApp("com.example.browser", "Browser"),
                InstalledApp("com.example.zebra", "Zebra"),
            ),
            launchable(),
        )
    }

    @Test
    fun anAppWithSeveralLauncherActivitiesIsListedOnce() {
        launcherActivity("com.example.suite", "Suite", activity = "com.example.suite.Writer")
        launcherActivity("com.example.suite", "Suite", activity = "com.example.suite.Sheets")

        assertEquals(listOf(InstalledApp("com.example.suite", "Suite")), launchable())
    }

    @Test
    fun anAppWithoutALabelIsNamedByItsPackage() {
        launcherActivity("com.example.unnamed", label = "")

        assertEquals(listOf(InstalledApp("com.example.unnamed", "com.example.unnamed")), launchable())
    }

    @Test
    fun aDeviceWithoutLauncherAppsHasNoList() {
        launcherActivity(context.packageName, "doona")

        assertEquals(emptyList<InstalledApp>(), launchable())
    }
}
