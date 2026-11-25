package dev.doona.android.feature.settings

import dev.doona.android.feature.model.RvAppearance
import kotlinx.coroutines.flow.Flow

/**
 * The app's own settings as 設定 reads and sets them. The app's is its preference store, the quick settings tile's
 * record and the per-app locale list; a test gives fixed ones.
 */
internal interface SettingsStore {
    /** Whether a dropped connection posts a notice. */
    val notifyOnDrop: Flow<Boolean>

    /** Whether the subscriptions update by themselves. */
    val autoUpdate: Flow<Boolean>

    /** How often they do, in hours: one of [RV_INTERVAL_HOURS]. */
    val updateHours: Flow<Int>

    /** The theme page's choices; 設定 shows the mode on 主題's row. */
    val appearance: Flow<RvAppearance>

    /** Whether the quick settings tile was recorded as added. */
    val tileAdded: Flow<Boolean>

    /** The release date (`YYYY-MM-DD`) of the GeoIP and GeoSite data the APK carries, which the core reports as no release. */
    val shippedGeoDate: String

    /** The app's per-app locale list, empty when it follows the phone's; Android changes it, so it is read each time. */
    fun languageTags(): String

    /** Sets the per-app locale list ([RvLanguage.tag][dev.doona.android.feature.model.RvLanguage.tag]); Android then shows the app again. */
    fun setLanguageTags(tags: String)

    suspend fun setNotifyOnDrop(enabled: Boolean)

    suspend fun setAutoUpdate(enabled: Boolean)

    suspend fun setUpdateHours(hours: Int)
}

/** How the system answered 加到快速設定: the tile was added, the dialog was dismissed, or the request failed. */
internal enum class TileAnswer { Added, Kept, Failed }

/**
 * What 設定 hands to Android: its VPN settings, where always-on is turned on (D14); the releases page, in the browser,
 * which is all 檢查更新 does (owner, 2026-11-23 21:35); and the request to add the quick settings tile, which answers
 * [TileAnswer.Failed] where Android has no such request (before 13), so the page tells how to add it by hand.
 */
internal interface SettingsSystem {
    fun openVpnSettings()

    fun openReleases()

    fun requestTile(onAnswer: (TileAnswer) -> Unit)
}

/**
 * The pages 設定 opens, which the app's navigation owns: 依 App, the DNS server, the effective config, 回報問題, 關於,
 * 主題, 語言, 更新間隔 and 實驗室.
 */
internal class SettingsNav(
    val perApp: () -> Unit,
    val dns: () -> Unit,
    val effectiveConfig: () -> Unit,
    val report: () -> Unit,
    val about: () -> Unit,
    val theme: () -> Unit,
    val language: () -> Unit,
    val interval: () -> Unit,
    val lab: () -> Unit,
)
