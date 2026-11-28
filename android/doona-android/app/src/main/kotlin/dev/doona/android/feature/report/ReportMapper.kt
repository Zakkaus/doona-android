package dev.doona.android.feature.report

import dev.doona.android.engine.CoreDiagnostics

/**
 * The preview of what a report holds, from what was read: honk's nodes, subscriptions and rules, the app's [events] and
 * the crash files the core lists ([diagnostics]). A count stays unknown when its source cannot be read.
 */
internal fun rvReportPreview(
    app: String,
    device: String,
    nodes: Int?,
    subscriptions: Int?,
    rules: Int?,
    events: Int?,
    diagnostics: CoreDiagnostics?,
): RvReportPreview = RvReportPreview(
    app = app,
    device = device,
    nodes = nodes,
    subscriptions = subscriptions,
    rules = rules,
    events = events,
    crashes = diagnostics?.crashes?.size,
)
