package dev.doona.android.feature.policies

import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine

/**
 * The sample engine with groups as a user may write them in honk by hand: no `Proxy`, so no main group; 出口, a selector
 * over two groups, two subscription nodes and a node added by hand (no subscription), that uses 香港; 香港, a url-test;
 * 日本, a fallback; and 空, a selector with no member.
 */
internal fun honkGroupsEngine(): FakeEngine = rvSampleEngine().apply {
    val byId = groups.value.associateBy { it.id }
    val hk = byId.getValue("g-HK")
    val jp = byId.getValue("g-JP")
    val nodes = byId.getValue("g-Proxy").members.take(2) + Member(HONK_BY_HAND, "Home Relay", latencyMs = 48)
    groups.value = listOf(
        Group(HONK_EXIT, "出口", GroupKind.Selector, listOf(Member("g-香港", "香港", isGroup = true), Member("g-日本", "日本", isGroup = true)) + nodes, selectedId = "g-香港"),
        Group("g-香港", "香港", GroupKind.UrlTest, hk.members, hk.selectedId),
        Group("g-日本", "日本", GroupKind.Fallback, jp.members, jp.members.first().id),
        Group("g-空", "空", GroupKind.Selector, emptyList(), selectedId = null),
    )
}

internal const val HONK_EXIT = "g-出口"

/** The node 出口 lists that no subscription holds. */
internal const val HONK_BY_HAND = "n-home-relay"
