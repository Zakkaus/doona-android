package dev.doona.android.engine.fake

import dev.doona.android.engine.ConfigApplied
import dev.doona.android.engine.ConfigCheck
import dev.doona.android.engine.ConfigDiagnostic
import dev.doona.android.engine.ConfigDraft
import dev.doona.android.engine.ConfigEditOp
import dev.doona.android.engine.ConfigEngine
import dev.doona.android.engine.ConfigFile
import dev.doona.android.engine.ConfigFileChange
import dev.doona.android.engine.ConfigRevision
import dev.doona.android.engine.ConfigTree
import dev.doona.android.engine.DiagnosticCode
import dev.doona.android.engine.DraftFrom
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.RevisionOrigin
import dev.doona.android.engine.SourceAt
import dev.doona.android.engine.SourceSpan
import dev.doona.android.engine.SupportRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The configuration commands of [FakeEngine] over [RvSampleConfig]'s tree: revisions are immutable maps of file ID to
 * text; a draft copies its base and counts its edits. Its check refuses a rule whose target after `->` names no group
 * the sample declares, and an import's include outside its root; it parses nothing else.
 */
internal class FakeConfig(
    private val now: () -> Long = { RvSampleConfig.NOW },
    private val revisions: MutableMap<Int, Map<String, String>> = RvSampleConfig.revisions().toMutableMap(),
    private val history: MutableList<ConfigRevision> = RvSampleConfig.history.toMutableList(),
) : ConfigEngine {
    private class Draft(val baseRev: Int, val origin: RevisionOrigin, val restored: Int?, var version: Int, val texts: MutableMap<String, String>, val paths: List<ConfigFile>?)

    private val lock = Mutex()
    private val drafts = mutableMapOf<String, Draft>()
    private var nextDraft = 1
    private val head get() = history.first().rev

    override suspend fun configRead(): Result<ConfigTree> = lock.withLock {
        val texts = revisions.getValue(head)
        Result.success(ConfigTree(head, RvSampleConfig.ENTRY, RvSampleConfig.files(texts), RvSampleConfig.summary, support(texts)))
    }

    override suspend fun configSource(at: SourceAt, fileId: String): Result<String> = lock.withLock {
        val texts = when (at) {
            is SourceAt.Revision -> revisions[at.rev]
            is SourceAt.Draft -> drafts[at.draft.id]?.texts
        }
        texts?.get(fileId)?.let { Result.success(it) } ?: failure(ErrorCode.NotFound)
    }

    override suspend fun configDraft(from: DraftFrom): Result<ConfigDraft> = lock.withLock {
        val draft = when (from) {
            DraftFrom.Head -> Draft(head, RevisionOrigin.Edit, null, 1, revisions.getValue(head).toMutableMap(), null)

            is DraftFrom.Revision -> revisions[from.rev]?.let { Draft(head, RevisionOrigin.Restore, from.rev, 1, it.toMutableMap(), null) } ?: return@withLock failure(ErrorCode.NotFound)

            is DraftFrom.Import -> when (from.token) {
                RvSampleConfig.IMPORT_LIMIT -> return@withLock failure(ErrorCode.SourceLimit)
                RvSampleConfig.IMPORT_ESCAPE -> Draft(head, RevisionOrigin.Import, null, 1, IMPORTED.toMutableMap(), IMPORTED_FILES)
                else -> return@withLock failure(ErrorCode.NotFound)
            }
        }
        val id = "d${nextDraft++}"
        drafts[id] = draft
        Result.success(ConfigDraft(id, draft.version, draft.baseRev))
    }

    override suspend fun configEdit(draft: ConfigDraft, fileId: String, op: ConfigEditOp): Result<ConfigDraft> = lock.withLock {
        val held = drafts[draft.id] ?: return@withLock failure(ErrorCode.NotFound)
        if (held.version != draft.version) return@withLock failure(ErrorCode.Conflict)
        if (fileId !in held.texts) return@withLock failure(ErrorCode.NotFound)
        when (op) {
            is ConfigEditOp.ReplaceText -> held.texts[fileId] = op.text
        }
        held.version++
        Result.success(draft.copy(version = held.version))
    }

    override suspend fun configCheck(draft: ConfigDraft): Result<ConfigCheck> = lock.withLock {
        val held = drafts[draft.id]?.takeIf { it.version == draft.version } ?: return@withLock failure(ErrorCode.Conflict)
        val base = revisions.getValue(held.baseRev)
        val files = held.paths ?: RvSampleConfig.files(held.texts)
        val diagnostics = held.texts.flatMap { (id, text) -> unknownTargets(id, text) } + escapes(held)
        val changes = files.mapNotNull { file ->
            val before = base[file.id].orEmpty().lines()
            val after = held.texts.getValue(file.id).lines()
            val kept = commonLines(before, after)
            ConfigFileChange(file.id, after.size - kept, before.size - kept).takeIf { it.added + it.removed > 0 }
        }
        Result.success(ConfigCheck(draft, diagnostics.isEmpty(), diagnostics, support(held.texts), changes, files))
    }

    override suspend fun configApply(draft: ConfigDraft): Result<ConfigApplied> = lock.withLock {
        val held = drafts[draft.id]?.takeIf { it.version == draft.version && it.baseRev == head } ?: return@withLock failure(ErrorCode.Conflict)
        if (held.texts.any { (id, text) -> unknownTargets(id, text).isNotEmpty() } || escapes(held).isNotEmpty()) return@withLock failure(ErrorCode.ParseFailed)
        val rev = head + 1
        revisions[rev] = held.texts.toMap()
        history.add(0, ConfigRevision(rev, now(), held.origin, held.restored))
        drafts.remove(draft.id)
        Result.success(ConfigApplied(rev, generation = rev + GENERATION_OFFSET))
    }

    override suspend fun configHistory(): Result<List<ConfigRevision>> = lock.withLock { Result.success(history.toList()) }

    override suspend fun configName(rev: Int, name: String?): Result<Unit> = lock.withLock {
        val at = history.indexOfFirst { it.rev == rev }.takeIf { it >= 0 } ?: return@withLock failure(ErrorCode.NotFound)
        history[at] = history[at].copy(name = name)
        Result.success(Unit)
    }

    /** Where [text] stands in file [fileId] of [texts]: its line and byte span; the sample's records each occur once. */
    private fun spanOf(texts: Map<String, String>, fileId: String, text: String): SourceSpan? {
        val source = texts[fileId] ?: return null
        val at = source.indexOf(text).takeIf { it >= 0 } ?: return null
        val start = source.substring(0, at).encodeToByteArray().size
        return SourceSpan(fileId, source.substring(0, at).count { it == '\n' } + 1, start, start + text.encodeToByteArray().size)
    }

    private fun support(texts: Map<String, String>): List<SupportRecord> =
        RvSampleConfig.skipped.mapNotNull { (file, text, reasons) -> spanOf(texts, file, text)?.let { SupportRecord(it, reasons) } }

    /** Each `-> name` whose name the sample does not declare, at the name. */
    private fun unknownTargets(fileId: String, text: String): List<ConfigDiagnostic> = TARGET.findAll(text).mapNotNull { match ->
        val name = match.groups[1] ?: return@mapNotNull null
        if (name.value in RvSampleConfig.targets) return@mapNotNull null
        val start = text.substring(0, name.range.first).encodeToByteArray().size
        val line = text.substring(0, name.range.first).count { it == '\n' } + 1
        ConfigDiagnostic(DiagnosticCode.UnknownGroup, SourceSpan(fileId, line, start, start + name.value.encodeToByteArray().size))
    }.toList()

    private fun escapes(draft: Draft): List<ConfigDiagnostic> =
        draft.texts.keys.mapNotNull { id -> spanOf(draft.texts, id, RvSampleConfig.ESCAPE_PATH)?.let { ConfigDiagnostic(DiagnosticCode.IncludeEscape, it) } }

    private fun <T> failure(code: ErrorCode): Result<T> = Result.failure(EngineException(code))

    private companion object {
        val TARGET = Regex("->\\s*([A-Za-z_][\\w-]*)")
        const val GENERATION_OFFSET = 6

        const val ROUTER = "i1"
        val IMPORTED = mapOf(ROUTER to router(), RvSampleConfig.NODES to RvSampleConfig.texts().getValue(RvSampleConfig.NODES))
        val IMPORTED_FILES = listOf(ConfigFile(ROUTER, "router.dae", null, 40), ConfigFile(RvSampleConfig.NODES, "nodes.dae", ROUTER, 12))

        /** The picked entry: line 4 includes a glob outside its directory; then 36 lines of rules. */
        fun router(): String = (
            listOf("# 路由器", "include {", "  nodes.dae", "  ${RvSampleConfig.ESCAPE_PATH}", "}", "routing {") +
                (1..33).map { "  dip(10.$it.0.0/16) -> direct" } + "}"
            ).joinToString("\n")

        /** The length of the longest run of lines both [a] and [b] keep in order. */
        fun commonLines(a: List<String>, b: List<String>): Int {
            var row = IntArray(b.size + 1)
            for (x in a) {
                val next = IntArray(b.size + 1)
                b.forEachIndexed { j, y -> next[j + 1] = if (x == y) row[j] + 1 else maxOf(row[j + 1], next[j]) }
                row = next
            }
            return row[b.size]
        }
    }
}
