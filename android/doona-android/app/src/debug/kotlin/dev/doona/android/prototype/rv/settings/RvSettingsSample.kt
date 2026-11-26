package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvSettingsState
import dev.doona.android.feature.model.RvThemeMode
import dev.doona.android.feature.report.RvReportPreview
import dev.doona.android.feature.settings.RvAboutVersions
import dev.doona.android.feature.settings.RvSettingsRoutes
import dev.doona.android.feature.settings.RvSettingsScreen
import dev.doona.android.feature.settings.RvThemePreviewPage
import dev.doona.android.feature.settings.RvUpdate
import dev.doona.android.prototype.rv.RvActivityScreenSample
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvSampleActivity
import java.time.LocalDate

/** 主題's 預覽 in the sandbox and the tests: the RV 活動 page on the sample, as the approved screens show it. */
internal val RV_SAMPLE_THEME_PREVIEW: RvThemePreviewPage = { RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {}) }

internal fun rvSampleAppearance() = RvAppearance(RvThemeMode.System, followWallpaper = false, accent = 0)

/** The settings page as [RvData] has it, with the sample's 每週 schedule (honk's default is 每天). */
internal fun rvSampleSettings() = RvSettingsState(
    notifyOnDrop = true,
    perApp = RvPerAppData.saved,
    autoUpdate = true,
    updateHours = RvData.UPDATE_HOURS,
    appearance = rvSampleAppearance(),
    dns = RvDnsUpstream.Cloudflare,
    geoInterval = RvGeoInterval.Weekly,
)

/** The sample's data: the copy the APK ships. */
internal val RV_SAMPLE_GEO_SHIPPED: LocalDate = LocalDate.of(2026, 11, 20)

/** The sample's newer data, as an update leaves it. */
internal val RV_SAMPLE_GEO_NEWER: LocalDate = LocalDate.of(2026, 11, 24)

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
    routes: RvSettingsRoutes = rvSettingsRoutes(),
    configRev: Int? = null,
    update: RvUpdate = RvUpdate.Idle,
) = RvSettingsScreen(state, rvSampleVersions.app, onChange, onOpenTheme, onTab, routes, configRev, RvGeoData(RV_SAMPLE_GEO_SHIPPED), update)

/** The sample's newer release. */
internal const val RV_SAMPLE_RELEASE = "0.2.0"
