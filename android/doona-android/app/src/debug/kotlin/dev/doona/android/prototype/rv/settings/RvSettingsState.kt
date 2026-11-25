package dev.doona.android.prototype.rv.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.vpn.QuickTileResult

/** Which theme the app wears: the system's, or one of the two. */
internal enum class RvThemeMode(@StringRes val label: Int) {
    System(R.string.rv_settings_theme_system),
    Light(R.string.rv_settings_theme_light),
    Dark(R.string.rv_settings_theme_dark),
}

/**
 * The theme page's choices: the mode, whether the wallpaper's colour is the accent, and the chosen swatch: 0 is the
 * scheme's own primary, 1 to 4 the fixed colours of [RV_ACCENTS].
 */
@Immutable
internal data class RvAppearance(val mode: RvThemeMode, val followWallpaper: Boolean, val accent: Int)

/** The DNS server the core asks (v0.2 picker): its name and address; the core sends the query through the main node. */
internal enum class RvDnsUpstream(@StringRes val label: Int, val address: String) {
    Cloudflare(R.string.rv_settings_dns_cloudflare, "1.1.1.1"),
    Google(R.string.rv_settings_dns_google, "8.8.8.8"),
    Quad9(R.string.rv_settings_dns_quad9, "9.9.9.9"),
    AliDns(R.string.rv_settings_dns_alidns, "223.5.5.5"),
}

/**
 * What the settings page shows. The appearance is the theme page's state, so the 主題 row reads what the page sets;
 * [perApp] is what 依 App saved. Until the quick settings tile is [tileAdded], 連線 offers 加到快速設定; [tileSheet] is
 * the sheet that tells how to add it by hand.
 */
@Immutable
internal data class RvSettingsState(
    val notifyOnDrop: Boolean,
    val perApp: RvPerApp,
    val autoUpdate: Boolean,
    val updateHours: Int,
    val appearance: RvAppearance,
    val dns: RvDnsUpstream,
    val language: RvLanguage = RvLanguage.TraditionalChinese,
    val tileAdded: Boolean = true,
    val tileSheet: Boolean = false,
) {
    /** How many apps 依 App checked. */
    val proxiedApps: Int get() = perApp.packages.size
}

/**
 * The page after the system answered 加到快速設定 with [result]: an added tile hides the row, a failed request opens the
 * sheet that tells how to add it by hand (its words are already there, a banner would need new ones), and a dismissed
 * dialog leaves the page as it was.
 */
internal fun RvSettingsState.afterTileRequest(result: QuickTileResult): RvSettingsState = when (result) {
    QuickTileResult.Added -> copy(tileAdded = true)
    QuickTileResult.Kept -> this
    QuickTileResult.Failed -> copy(tileSheet = true)
}
