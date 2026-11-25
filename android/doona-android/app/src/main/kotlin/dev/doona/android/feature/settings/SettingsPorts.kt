package dev.doona.android.feature.settings

import dev.doona.android.feature.model.RvAppearance
import kotlinx.coroutines.flow.Flow

/**
 * The app's own settings as 設定 reads and sets them. The app's is its preference store, the quick settings tile's
 * record and the per-app locale list; a test gives fixed ones.
 *
 * Each set returns at once and the store writes in a scope of its own that outlives every page, so a choice made just
 * before the user leaves the page is still stored; writes land in the order they were asked for.
 */
internal interface SettingsStore {
    /** Whether a dropped connection posts a notice. */
    val notifyOnDrop: Flow<Boolean>

    /** Whether the subscriptions update by themselves. */
    val autoUpdate: Flow<Boolean>

    /** How often they do, in hours: one of [updateIntervals] or a count in [updateHoursRange]. */
    val updateHours: Flow<Int>

    /** The preset intervals, in hours, shortest first: 更新間隔's radios. */
    val updateIntervals: List<Int>

    /** The counts of hours 更新間隔's 自訂 can store. */
    val updateHoursRange: IntRange

    /** The theme page's choices; 設定 shows the mode on 主題's row. */
    val appearance: Flow<RvAppearance>

    /** Stores the theme page's choices; the app's theme follows them. */
    fun setAppearance(appearance: RvAppearance)

    /** Whether the quick settings tile was recorded as added. */
    val tileAdded: Flow<Boolean>

    /** The release date (`YYYY-MM-DD`) of the GeoIP and GeoSite data the APK carries, which the core reports as no release. */
    val shippedGeoDate: String

    /** The app's per-app locale list, empty when it follows the phone's; Android changes it, so it is read each time. */
    fun languageTags(): String

    /** Sets the per-app locale list ([RvLanguage.tag][dev.doona.android.feature.model.RvLanguage.tag]); Android then shows the app again. */
    fun setLanguageTags(tags: String)

    fun setNotifyOnDrop(enabled: Boolean)

    fun setAutoUpdate(enabled: Boolean)

    /** Stores [hours], which must be in [updateHoursRange]. */
    fun setUpdateHours(hours: Int)

    /** 實驗室's 效能模式 as stored, which the core reads only when it starts. */
    val performanceMode: Flow<Boolean>

    /** 效能模式 as this run of the app started with it: the stored value read once per process. */
    suspend fun performanceAtStart(): Boolean

    fun setPerformanceMode(enabled: Boolean)
}

/** How the system answered 加到快速設定: the tile was added, the dialog was dismissed, or the request failed. */
internal enum class TileAnswer { Added, Kept, Failed }

/**
 * What 設定 hands to Android: its VPN settings, where always-on is turned on (D14); the releases page, in the browser,
 * where a newer release is downloaded (the app downloads nothing itself, owner 2026-11-23 21:35); the repository's page,
 * for 關於's GitHub 專案; and the request to add the quick settings tile, which answers [TileAnswer.Failed] where Android
 * has no such request (before 13), so the page tells how to add it by hand.
 */
internal interface SettingsSystem {
    fun openVpnSettings()

    fun openReleases()

    fun openRepository()

    fun requestTile(onAnswer: (TileAnswer) -> Unit)
}

/**
 * 檢查更新's question to GitHub: the tag of the app's latest published release, null when none is published yet, or a
 * failure when GitHub gave no usable answer (offline, refused, not a release).
 */
internal fun interface ReleaseCheck {
    suspend fun latest(): Result<String?>
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
