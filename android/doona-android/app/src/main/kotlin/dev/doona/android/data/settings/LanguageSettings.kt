package dev.doona.android.data.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings

object LanguageSettings {
    /** Empty when the app follows the system language, including on Android 12. */
    fun languageTags(context: Context): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else {
        ""
    }

    /** Empty [tags] restores the system language. Returns false before Android 13. */
    fun setLanguageTags(context: Context, tags: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
        return true
    }

    fun openSettings(activity: Activity) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", activity.packageName, null))
        } else {
            Intent(Settings.ACTION_LOCALE_SETTINGS)
        }
        activity.startActivity(intent)
    }
}
