package dev.doona.android.vpn

import dev.doona.android.engine.ConfigDraft
import dev.doona.android.engine.ConfigEditOp
import dev.doona.android.engine.ConfigEngine
import dev.doona.android.engine.DraftFrom
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.SourceAt

/**
 * The configuration commands of [ServiceEngine] until slice 12 decodes schema 4 (config-plan.md, row 12): the core of
 * this build does not have them, so each fails as an internal error and the pages show their failure banner.
 */
internal object UnwiredConfig : ConfigEngine {
    private fun <T> unwired(): Result<T> = Result.failure(EngineException(ErrorCode.Internal))

    override suspend fun configRead() = unwired<Nothing>()
    override suspend fun configSource(at: SourceAt, fileId: String) = unwired<Nothing>()
    override suspend fun configDraft(from: DraftFrom) = unwired<Nothing>()
    override suspend fun configEdit(draft: ConfigDraft, fileId: String, op: ConfigEditOp) = unwired<Nothing>()
    override suspend fun configCheck(draft: ConfigDraft) = unwired<Nothing>()
    override suspend fun configApply(draft: ConfigDraft) = unwired<Nothing>()
    override suspend fun configHistory() = unwired<Nothing>()
    override suspend fun configName(rev: Int, name: String?) = unwired<Nothing>()
}
