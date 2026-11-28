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
    fun languageTags(context: Context): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else {
        context.getSharedPreferences("locale", Context.MODE_PRIVATE).getString("tags", null).orEmpty()
    }

    fun setLanguageTags(context: Context, tags: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
        } else {
            context.getSharedPreferences("locale", Context.MODE_PRIVATE).edit().putString("tags", tags.ifEmpty { null }).apply()
        }
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
