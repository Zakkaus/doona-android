package dev.doona.android.feature.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import java.util.Locale

/** The app's languages, each named in itself, as a language list names them; [System] follows the phone's. */
internal enum class RvLanguage(@StringRes val label: Int, val tag: String) {
    System(R.string.rv_settings_theme_system, ""),
    English(R.string.rv_language_english, "en"),
    TraditionalChinese(R.string.rv_language_zh_hant, "zh-TW"),
    SimplifiedChinese(R.string.rv_language_zh_hans, "zh-CN"),
    ;

    companion object {
        /**
         * The choice a per-app locale list ([tag]s as `LanguageSettings.languageTags` gives them) stands for: none is
         * [System]; Chinese is Traditional when its script is Hant or its region Taiwan, Hong Kong or Macao, else
         * Simplified; any other language shows the app's English.
         */
        fun of(tags: String): RvLanguage {
            val first = tags.substringBefore(',').trim()
            if (first.isEmpty()) return System
            val locale = Locale.forLanguageTag(first)
            if (locale.language != "zh") return English
            val traditional = locale.script == "Hant" || (locale.script.isEmpty() && locale.country in TRADITIONAL_REGIONS)
            return if (traditional) TraditionalChinese else SimplifiedChinese
        }

        private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")
    }
}

/** Which theme the app wears: the system's, or one of the two. */
internal enum class RvThemeMode(@StringRes val label: Int) {
    System(R.string.rv_settings_theme_system),
    Light(R.string.rv_settings_theme_light),
    Dark(R.string.rv_settings_theme_dark),
}

/**
 * The theme page's choices: the mode, whether the wallpaper's colour is the accent, and the chosen swatch: 0 is the
 * scheme's own primary, 1 to 4 the theme page's fixed accent colours.
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
