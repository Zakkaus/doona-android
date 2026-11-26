package dev.doona.android.feature.report

import dev.doona.android.engine.CoreDiagnostics

/**
 * The preview of what a report holds, from what was read: honk's nodes, subscriptions and rules, the app's [events] and
 * the crash files the core lists ([diagnostics]). A part not read (null) counts 0: the export reads it again and says
 * what it could not read.
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
    nodes = nodes ?: 0,
    subscriptions = subscriptions ?: 0,
    rules = rules ?: 0,
    events = events ?: 0,
    crashes = diagnostics?.crashes?.size ?: 0,
)
