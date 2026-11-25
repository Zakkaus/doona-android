package dev.doona.android.feature.model

import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Member

/**
 * Every node the catalog's groups list, once each, in the catalog's order: a nested group is a member that is a group, so
 * it is left out here and its own nodes are listed under it. The one helper for a group's nodes (the 節點 pages, the
 * policy pages).
 */
internal fun rvLeafMembers(catalog: Catalog): List<Member> =
    catalog.groups.asSequence().flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }.toList()
