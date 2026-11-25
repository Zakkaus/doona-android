package dev.doona.android.prototype.rv.settings

import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.report.RvReportActions
import dev.doona.android.feature.settings.RvAboutRoutes
import dev.doona.android.feature.settings.RvSettingsRoutes

/**
 * The sandbox's 設定 routes: each one the caller does not name stays [NOT_WIRED], as the page's own defaults did before
 * the page moved to main, where every route is required.
 */
internal fun rvSettingsRoutes(
    alwaysOn: () -> Unit = NOT_WIRED,
    addTile: () -> Unit = NOT_WIRED,
    perApp: () -> Unit = NOT_WIRED,
    dns: () -> Unit = NOT_WIRED,
    effectiveConfig: () -> Unit = NOT_WIRED,
    report: () -> Unit = NOT_WIRED,
    about: () -> Unit = NOT_WIRED,
    config: () -> Unit = NOT_WIRED,
    geoUpdate: () -> Unit = NOT_WIRED,
    checkUpdate: () -> Unit = NOT_WIRED,
    release: () -> Unit = NOT_WIRED,
    language: () -> Unit = NOT_WIRED,
    interval: () -> Unit = NOT_WIRED,
    lab: () -> Unit = NOT_WIRED,
) = RvSettingsRoutes(alwaysOn, addTile, perApp, dns, effectiveConfig, report, about, config, geoUpdate, checkUpdate, release, language, interval, lab)

/** 關於's routes in the sandbox, [NOT_WIRED] unless named. */
internal fun rvAboutRoutes(
    github: () -> Unit = NOT_WIRED,
    report: () -> Unit = NOT_WIRED,
    licenses: () -> Unit = NOT_WIRED,
    privacy: () -> Unit = NOT_WIRED,
) = RvAboutRoutes(github, report, licenses, privacy)

/** 回報問題's actions in the sandbox, [NOT_WIRED] unless named. */
internal fun rvReportActions(
    github: () -> Unit = NOT_WIRED,
    share: () -> Unit = NOT_WIRED,
    retry: () -> Unit = NOT_WIRED,
) = RvReportActions(github, share, retry)
