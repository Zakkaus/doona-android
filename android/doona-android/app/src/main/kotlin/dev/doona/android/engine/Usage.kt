package dev.doona.android.engine

/** Today and yesterday by the hour (null: yesterday was not kept), and the finished days of the last 31 that had bytes. */
data class Usage(val today: DayUsage, val yesterday: DayUsage?, val days: List<DayTotal>)

/**
 * One local [date] (`2026-11-22`): 24 hours of payload bytes each way and of flows opened, the bytes of each route
 * target, and in [Mode.Rule] each rule's. Each list keeps 64 ids a day and counts the rest under `other`.
 */
data class DayUsage(
    val date: String,
    val up: List<Long>,
    val down: List<Long>,
    val flows: List<Long>,
    val outbounds: List<OutboundBytes>,
    val byRule: List<RuleBytes> = emptyList(),
)

/** A route target's payload: [id] is a group (`g-Proxy`), a node, `direct` or `other`. */
data class OutboundBytes(val id: String, val up: Long, val down: Long)

/**
 * The payload and flows a rule routed in [Mode.Rule]: [id] is a [RuleRow.id], `unmatched` for flows no row matched,
 * which went to the main group, or `other`. Flows of the other modes count in [DayUsage.outbounds] only.
 */
data class RuleBytes(val id: String, val up: Long, val down: Long, val flows: Long)

data class DayTotal(val date: String, val up: Long, val down: Long)
