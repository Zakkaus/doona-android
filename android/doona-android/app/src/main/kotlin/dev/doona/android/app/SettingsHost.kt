package dev.doona.android.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.LanguageSettings
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.data.settings.ThemeMode
import dev.doona.android.data.settings.UpdateCheck
import dev.doona.android.data.settings.UpdateInterval
import dev.doona.android.engine.LogLevel
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvThemeMode
import dev.doona.android.feature.settings.LogLevels
import dev.doona.android.feature.settings.SettingsStore
import dev.doona.android.feature.settings.SettingsSystem
import dev.doona.android.feature.settings.TileAnswer
import dev.doona.android.vpn.QuickTile
import dev.doona.android.vpn.QuickTileResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.hours

/**
 * 設定's store in the app: [settings] for the switches, the interval and the theme, [tile] for the quick settings
 * tile, Android's per-app locale list for the language. [shippedGeoDate] is the APK's GeoIP/GeoSite release
 * (`crates/doona-core/tools/geo-pins.json`'s `release_date`), until the build passes it on.
 */
internal class AppSettingsStore(
    private val context: Context,
    private val settings: AppSettings,
    private val tile: QuickTile,
    override val shippedGeoDate: String = SHIPPED_GEO_DATE,
) : SettingsStore {
    override val notifyOnDrop: Flow<Boolean> = settings.notifyOnDrop
    override val autoUpdate: Flow<Boolean> = settings.subscriptionUpdate.map { it.enabled }
    override val updateHours: Flow<Int> = settings.subscriptionUpdate.map { it.interval.duration.inWholeHours.toInt() }
    override val appearance: Flow<RvAppearance> = settings.appearance.map {
        RvAppearance(rvThemeMode(it.mode), it.followWallpaper, it.accent.ordinal)
    }
    override val tileAdded: Flow<Boolean> = tile.added

    override fun languageTags(): String = LanguageSettings.languageTags(context)

    override fun setLanguageTags(tags: String) {
        LanguageSettings.setLanguageTags(context, tags)
    }

    override suspend fun setNotifyOnDrop(enabled: Boolean) = settings.setNotifyOnDrop(enabled)

    override suspend fun setAutoUpdate(enabled: Boolean) = settings.setAutoUpdate(enabled)

    override suspend fun setUpdateHours(hours: Int) {
        UpdateInterval.entries.firstOrNull { it.duration == hours.hours }?.let { settings.setUpdateInterval(it) }
    }

    private fun rvThemeMode(mode: ThemeMode) = when (mode) {
        ThemeMode.System -> RvThemeMode.System
        ThemeMode.Light -> RvThemeMode.Light
        ThemeMode.Dark -> RvThemeMode.Dark
    }

    private companion object {
        const val SHIPPED_GEO_DATE = "2026-11-23"
    }
}

/** What 設定 hands to Android from [activity]: its VPN settings, the releases page, and the quick settings [tile]'s request. */
internal class ActivitySettingsSystem(private val activity: Activity, private val tile: QuickTile) : SettingsSystem {
    override fun openVpnSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // A phone without the page: always-on cannot be set from here.
        }
    }

    override fun openReleases() {
        UpdateCheck.openReleases(activity)
    }

    override fun requestTile(onAnswer: (TileAnswer) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return onAnswer(TileAnswer.Failed)
        tile.request(activity) { result ->
            onAnswer(
                when (result) {
                    QuickTileResult.Added -> TileAnswer.Added
                    QuickTileResult.Kept -> TileAnswer.Kept
                    QuickTileResult.Failed -> TileAnswer.Failed
                },
            )
        }
    }
}

/** 日誌等級's levels: the app's process-wide [controller]. */
internal class AppLogLevels(private val controller: LogLevelController) : LogLevels {
    override val level: StateFlow<LogLevel> get() = controller.level

    override suspend fun set(level: LogLevel): Result<Unit> = controller.set(level)
}
