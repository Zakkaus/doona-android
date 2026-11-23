package dev.doona.android.data.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Intent
import android.os.LocaleList
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class SettingsActionsTest {
    @Test
    fun releaseCheckHandsOffToTheBrowserWithoutSendingAppData() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertTrue(UpdateCheck.openReleases(activity))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/Zakkaus/doona-android/releases", intent.dataString)
        assertNull(intent.extras)
    }

    @Test
    @Config(sdk = [33])
    fun languageUsesTheAppLocaleAndOpensThisAppsSettings() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val manager = activity.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.forLanguageTags("zh-TW")
        assertEquals("zh-TW", LanguageSettings.languageTags(activity))
        assertTrue(LanguageSettings.setLanguageTags(activity, ""))
        assertTrue(manager.applicationLocales.isEmpty)

        LanguageSettings.openSettings(activity)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_LOCALE_SETTINGS, intent.action)
        assertEquals("package:${activity.packageName}", intent.dataString)
    }

    @Test
    @Config(sdk = [31])
    fun android12OnlyOffersSystemLanguageSettings() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertFalse(LanguageSettings.setLanguageTags(activity, "zh-TW"))
        LanguageSettings.openSettings(activity)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_LOCALE_SETTINGS, intent.action)
        assertNull(intent.data)
    }
}
