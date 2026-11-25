package dev.doona.android.feature.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/**
 * How doona routes (item 24): by rules, where the main node (the final group's choice) carries what no rule sends
 * elsewhere; everything through one node; or nothing through a node. The engine's mode has no rules yet, so the sample
 * pages take this one.
 */
internal enum class RvRouteMode { Rule, Global, Direct }

/**
 * A rule group that sent traffic today: its name, the node it sent it to (null when it goes direct), and its bytes; a
 * group that chooses per network (a sample) gives the node its UDP uses in [udpNodeId] when that is another node.
 */
@Immutable
internal class RvRuleOutbound(@StringRes val group: Int, val nodeId: String?, val bytes: Long, val udpNodeId: String? = null)
