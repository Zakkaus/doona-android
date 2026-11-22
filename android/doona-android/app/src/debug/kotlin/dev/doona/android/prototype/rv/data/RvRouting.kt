package dev.doona.android.prototype.rv.data

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * How doona routes (item 24): by rules, where the main node (the final group's choice) carries what no rule sends
 * elsewhere; everything through one node; or nothing through a node. The engine's mode has no rules yet, so the sample
 * pages take this one.
 */
internal enum class RvRouteMode { Rule, Global, Direct }

/** A rule group that sent traffic today: its name, the node it sent it to (null when it goes direct), and its bytes. */
@Immutable
internal class RvRuleOutbound(@StringRes val group: Int, val nodeId: String?, val bytes: Long)

/**
 * What one outbound carried of the last minute, in whole percent: the main node ([group] null, [nodeId] the node), a rule
 * group through its own node, or a rule group that goes direct ([nodeId] null). With neither, everything went direct.
 */
@Immutable
internal class RvMinuteShare(@StringRes val group: Int?, val nodeId: String?, val percent: Int) {
    val direct: Boolean get() = nodeId == null
}

/**
 * One outbound in the outbound card's alternatives, with today's bytes: the main node ([group] null, [nodeId] the node),
 * a rule group through its node or direct ([nodeId] null), or the [folded] rule groups past the card's limit, as 其他.
 */
@Immutable
internal class RvOutboundPart(@StringRes val group: Int?, val nodeId: String?, val bytes: Long, val folded: Int = 0) {
    val other: Boolean get() = folded > 0
    val main: Boolean get() = group == null && !other
    val direct: Boolean get() = nodeId == null && !other
}

/**
 * [parts] as whole percents of their sum that add up to 100, by the largest remainder, so the shares shown add up as the
 * bytes do; all zero when there is nothing.
 */
internal fun rvPercents(parts: List<Long>): List<Int> {
    val total = parts.sum()
    if (total <= 0L) return parts.map { 0 }
    val exact = parts.map { it * PERCENT.toDouble() / total }
    val floors = exact.map { it.toInt() }.toMutableList()
    val short = PERCENT - floors.sum()
    exact.indices.sortedByDescending { exact[it] - floors[it] }.take(short).forEach { floors[it]++ }
    return floors
}

private const val PERCENT = 100
