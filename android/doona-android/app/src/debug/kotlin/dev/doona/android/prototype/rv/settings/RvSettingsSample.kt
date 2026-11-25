package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvSettingsState
import dev.doona.android.feature.model.RvThemeMode
import dev.doona.android.prototype.rv.data.RvData

internal fun rvSampleAppearance() = RvAppearance(RvThemeMode.System, followWallpaper = false, accent = 0)

/** The settings page as [RvData] has it. */
internal fun rvSampleSettings() = RvSettingsState(
    notifyOnDrop = true,
    perApp = RvPerAppData.saved,
    autoUpdate = true,
    updateHours = RvData.UPDATE_HOURS,
    appearance = rvSampleAppearance(),
    dns = RvDnsUpstream.Cloudflare,
)

/** The sample's data: the copy the APK ships, 2026-11-20 (UTC). */
internal const val RV_SAMPLE_GEO_SHIPPED_MS = 1_795_132_800_000L

/** The sample's newer data, 2026-11-24 (UTC), as an update leaves it. */
internal const val RV_SAMPLE_GEO_NEWER_MS = 1_795_478_400_000L

/** The sample's versions: the app at the build files' 0.1.0, honk at the core's pinned daeuniverse/honk revision. */
internal val rvSampleVersions = RvAboutVersions(app = "0.1.0", honk = "8919784")

/** The sample's report: the app of [rvSampleVersions], the sample data's counts, the last 200 events, 2 crashes. */
internal val rvSampleReport = RvReportPreview("0.1.0", "Pixel 8 · Android 15", nodes = 12, subscriptions = 2, rules = 14, events = 200, crashes = 2)

/** 設定 on the sample: the GeoIP/GeoSite copy the APK ships. The page takes it as a parameter. */
@Composable
internal fun RvSettingsScreenSample(
    state: RvSettingsState,
    onChange: (RvSettingsState) -> Unit,
    onOpenTheme: () -> Unit,
    onTab: (Int) -> Unit = {},
    routes: RvSettingsRoutes = RvSettingsRoutes(),
    configRev: Int? = null,
    update: RvUpdate = RvUpdate.Idle,
) = RvSettingsScreen(state, onChange, onOpenTheme, onTab, routes, configRev, RvGeoData(RV_SAMPLE_GEO_SHIPPED_MS), update)

/** The sample's newer release. */
internal const val RV_SAMPLE_RELEASE = "0.2.0"
