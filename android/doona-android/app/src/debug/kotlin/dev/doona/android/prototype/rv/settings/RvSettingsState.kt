package dev.doona.android.prototype.rv.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvData

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

/** What the settings page shows. The appearance is the theme page's state, so the 主題 row reads what the page sets. */
@Immutable
internal data class RvSettingsState(
    val notifyOnDrop: Boolean,
    val proxiedApps: Int,
    val autoUpdate: Boolean,
    val updateHours: Int,
    val appearance: RvAppearance,
    val dns: RvDnsUpstream,
)

internal fun rvSampleAppearance() = RvAppearance(RvThemeMode.System, followWallpaper = false, accent = 0)

/** The settings page as [RvData] has it. */
internal fun rvSampleSettings() = RvSettingsState(
    notifyOnDrop = true,
    proxiedApps = RvData.PROXIED_APPS,
    autoUpdate = true,
    updateHours = RvData.UPDATE_HOURS,
    appearance = rvSampleAppearance(),
    dns = RvDnsUpstream.Cloudflare,
)
