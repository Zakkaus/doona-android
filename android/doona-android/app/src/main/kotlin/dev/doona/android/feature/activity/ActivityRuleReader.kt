package dev.doona.android.feature.activity

import dev.doona.android.engine.Engine
import dev.doona.android.engine.RuleRow

/**
 * The rule rows today's `byRule` ids name, read from [engine] a page of [PAGE] rows at a time: only as far as the ids
 * need, and again from the start when the rules' revision changes. One collector calls it at a time; a page is kept
 * only once it is read whole, so a cancelled read leaves what was read before.
 */
internal class ActivityRuleReader(private val engine: Engine) {
    private var revision: Long? = null
    private val rows = HashMap<String, RuleRow>()
    private var offset = 0
    private var total = Int.MAX_VALUE

    /** The rows of [ids] at [rulesRevision], as far as the list has them; a failed read returns what is known. */
    suspend fun resolve(rulesRevision: Long?, ids: Collection<String>): Map<String, RuleRow> {
        if (rulesRevision != revision) restart(rulesRevision)
        var restarts = 0
        while (offset < total && !ids.all(rows::containsKey)) {
            val page = engine.rules(offset, PAGE).getOrNull() ?: break
            if (page.rulesRevision != revision) {
                // The rules changed under the read: start again at this revision, twice at most per call.
                if (restarts++ == MAX_RESTARTS) break
                restart(page.rulesRevision)
                continue
            }
            if (page.rows.isEmpty()) break
            page.rows.forEach { rows[it.id] = it }
            offset += page.rows.size
            total = page.total
        }
        return ids.mapNotNull(rows::get).associateBy { it.id }
    }

    private fun restart(to: Long?) {
        revision = to
        rows.clear()
        offset = 0
        total = Int.MAX_VALUE
    }

    private companion object {
        const val PAGE = 50
        const val MAX_RESTARTS = 2
    }
}
