package dev.doona.android.feature.config

import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.engine.ConfigCheck
import dev.doona.android.engine.ConfigEngine
import dev.doona.android.engine.ConfigFile
import dev.doona.android.engine.ConfigRevision
import dev.doona.android.engine.ConfigTree
import dev.doona.android.engine.SourceAt
import dev.doona.android.engine.SourceSpan

/** The text of [span] in [text]: its UTF-8 bytes `[start, end)`, as the core counts them. */
internal fun rvSpanText(text: String, span: SourceSpan): String = text.encodeToByteArray().decodeToString(span.start, span.end)

/** The texts of the files [ids] at [at], read one by one; the first failure is the result. */
internal suspend fun ConfigEngine.texts(at: SourceAt, ids: Collection<String>): Result<Map<String, String>> {
    val texts = LinkedHashMap<String, String>()
    for (id in ids.distinct()) texts[id] = configSource(at, id).getOrElse { return Result.failure(it) }
    return Result.success(texts)
}

/** The manifest's files as the pages show them: each path, and the path of the file that includes it. */
internal fun rvConfigFiles(files: List<ConfigFile>): List<RvConfigFile> {
    val paths = files.associate { it.id to it.path }
    return files.map { RvConfigFile(it.path, it.parentId?.let(paths::get), it.lines) }
}

/**
 * The overview of [tree] applied at [appliedAt]: its files, summary and support records, each skipped one spelled as
 * [texts] (the head's text of every file that holds one) has it at its span, in the order the core lists them.
 */
internal fun rvConfigState(tree: ConfigTree, appliedAt: Long, texts: Map<String, String>, nowEpochMs: Long): RvConfigState {
    val paths = tree.files.associate { it.id to it.path }
    val skipped = tree.support.filter { it.skipped }.map { record ->
        RvSkipped(rvSpanText(texts.getValue(record.at.fileId), record.at), paths.getValue(record.at.fileId), record.at.line, record.reasons.map(::rvSkipReason))
    }
    return RvConfigState(tree.rev, appliedAt, rvConfigFiles(tree.files), rvConfigSummary(tree), skipped, tree.support.count { !it.skipped }, nowEpochMs)
}

private fun rvConfigSummary(tree: ConfigTree) = with(tree.summary) {
    RvConfigSummary(subscriptions, nodes, groups, trafficRules, dnsUpstreams, dnsRequestRules, dnsResponseRules)
}

internal fun rvRevision(revision: ConfigRevision): RvRevision =
    RvRevision(revision.rev, revision.appliedAtEpochMs, rvRevisionOrigin(revision.origin), revision.restored, revision.name)

/** The line diffs of every file [check] changed: its base revision's text against the draft's, in manifest order. */
internal suspend fun ConfigEngine.rvCheckDiff(check: ConfigCheck): Result<List<RvDiffFile>> {
    val ids = check.changes.map { it.fileId }
    val paths = check.files.associate { it.id to it.path }
    val base = texts(SourceAt.Revision(check.draft.baseRev), ids).getOrElse { return Result.failure(it) }
    val draft = texts(SourceAt.Draft(check.draft), ids).getOrElse { return Result.failure(it) }
    return Result.success(ids.mapNotNull { rvLineDiff(paths.getValue(it), base.getValue(it), draft.getValue(it)) })
}
