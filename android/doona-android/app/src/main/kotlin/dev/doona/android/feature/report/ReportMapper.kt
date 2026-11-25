package dev.doona.android.feature.report

import dev.doona.android.engine.Catalog
import dev.doona.android.engine.CoreDiagnostics
import dev.doona.android.engine.RulePage
import dev.doona.android.engine.Subscription
import dev.doona.android.report.nodeMembers

/**
 * The preview of what a report holds, from what was read: the [catalog]'s nodes, the [subscriptions], the rule rows
 * ([rules]), the app's [events] and the crash files the core lists ([diagnostics]). A part not read (null) counts 0:
 * the export reads it again and says what it could not read.
 */
internal fun rvReportPreview(
    app: String,
    device: String,
    catalog: Catalog?,
    subscriptions: List<Subscription>,
    rules: RulePage?,
    events: Int?,
    diagnostics: CoreDiagnostics?,
): RvReportPreview = RvReportPreview(
    app = app,
    device = device,
    nodes = catalog?.nodeMembers()?.size ?: 0,
    subscriptions = subscriptions.size,
    rules = rules?.total ?: 0,
    events = events ?: 0,
    crashes = diagnostics?.crashes?.size ?: 0,
)
