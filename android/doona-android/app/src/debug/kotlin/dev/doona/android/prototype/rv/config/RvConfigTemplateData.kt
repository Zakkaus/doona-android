package dev.doona.android.prototype.rv.config

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.prototype.rv.RvDiffFile
import dev.doona.android.prototype.rv.RvDiffKind.Added
import dev.doona.android.prototype.rv.RvDiffKind.Same
import dev.doona.android.prototype.rv.RvDiffLine
import dev.doona.android.prototype.rv.policy.RvChange
import dev.doona.android.prototype.rv.policy.RvTemplate

/** How a template's group picks: doona's templates write `policy: score` or `policy: select`, named as doona names them. */
internal enum class RvTemplatePolicy(@StringRes val label: Int, @DrawableRes val glyph: Int) {
    Score(R.string.policy_kind_score, R.drawable.rv_sym_bolt),
    Select(R.string.policy_kind_selector, R.drawable.rv_sym_touch_app),
}

/**
 * A group a template declares (doona `templates.ts` `GroupSpec`): its [name] in the file, its [label] (doona
 * `templateText`: the title every page gives it, and the comment a template writes over it), how it picks, and a
 * region's flag.
 */
@Immutable
internal class RvTemplateGroup(val name: String, @StringRes val label: Int, val policy: RvTemplatePolicy, val flag: String? = null)

/** A template on the configuration side: the row it shows and the groups it declares (doona `templates[id].groups`). */
@Immutable
internal class RvConfigTemplate(val row: RvTemplate, val groups: List<RvTemplateGroup>) {
    /** A template without groups routes to the file's first group; a new file gets doona's default `proxy`. */
    val written: List<RvTemplateGroup> get() = groups.ifEmpty { listOf(RvConfigTemplateData.defaultProxy) }
}

/** doona's two options, each written into every template's routing, with doona's defaults. */
@Immutable
internal data class RvTemplateOptions(val blockAds: Boolean = false, val blockQuic: Boolean = true)

/** A group in the review before a template is applied: one the template adds, or one a file already declares (沿用). */
@Immutable
internal class RvTemplateGroupChange(val group: RvTemplateGroup, val change: RvChange)

/**
 * Applying [to] over [from] to the file that holds the routing (doona `writeTemplate`): the rules it adds and removes,
 * the groups it adds or reuses, and the file's diff against revision [baseRev].
 */
@Immutable
internal class RvTemplateApply(
    val from: RvTemplate,
    val to: RvTemplate,
    val added: Int,
    val removed: Int,
    val groups: List<RvTemplateGroupChange>,
    val file: RvDiffFile,
    val baseRev: Int,
)

/**
 * doona's templates (`plans/refs-doona/src/dae/templates.ts`, 2026-11-22): 繞過中國大陸, 只代理被封鎖的網站 and 全域代理
 * first, then 更多範本 (單一策略組, 依服務分流, 依地區分流, 回國); its region groups (six regions with their flags, and
 * 回國節點) and service groups. The sample applies 依服務分流 to a `config.dae` that 繞過中國大陸 wrote with the
 * default options.
 */
internal object RvConfigTemplateData {
    /** doona's `defaultGroup`, written without a comment into a file with no group; titled 代理 as doona's `proxy`. */
    val defaultProxy = RvTemplateGroup("proxy", R.string.rv_template_group_proxy, RvTemplatePolicy.Score)

    private val proxy = RvTemplateGroup("proxy", R.string.rv_template_group_proxy, RvTemplatePolicy.Select)
    private val auto = RvTemplateGroup("auto", R.string.rv_template_group_auto, RvTemplatePolicy.Score)
    private fun service(name: String, @StringRes label: Int) = RvTemplateGroup(name, label, RvTemplatePolicy.Select)
    private fun region(code: String, @StringRes label: Int) = RvTemplateGroup(code, label, RvTemplatePolicy.Score, code)

    private val regionGroups = listOf(
        region("hk", R.string.rv_nodes_place_hk),
        region("jp", R.string.rv_nodes_place_jp),
        region("us", R.string.rv_nodes_place_us),
        region("tw", R.string.rv_nodes_place_tw),
        region("sg", R.string.rv_nodes_place_sg),
        region("kr", R.string.rv_nodes_place_kr),
    )
    private val telegram = service("telegram", R.string.rv_template_group_telegram)
    private val media = service("media", R.string.rv_template_group_media)
    private val apple = service("apple", R.string.rv_template_group_apple)

    val bypass = RvConfigTemplate(RvTemplate("bypass", R.string.rv_policy_template_value, R.string.rv_template_cn_direct_line, R.drawable.rv_sym_arrow_right_alt), emptyList())
    private val gfw = RvConfigTemplate(RvTemplate("gfw", R.string.rv_template_gfw, R.string.rv_template_gfw_line, R.drawable.rv_sym_block), emptyList())
    private val global = RvConfigTemplate(RvTemplate("global", R.string.rv_template_global, R.string.rv_template_global_line, R.drawable.rv_settings_ic_language), emptyList())
    private val single = RvConfigTemplate(RvTemplate("single", R.string.rv_template_single, R.string.rv_template_single_line, R.drawable.rv_sym_vpn_lock), listOf(proxy, auto))
    val services = RvConfigTemplate(
        RvTemplate("services", R.string.rv_template_services, R.string.rv_template_by_service_line, R.drawable.rv_sym_apps),
        listOf(proxy, auto, telegram, media, apple),
    )
    val regions = RvConfigTemplate(
        RvTemplate("regions", R.string.rv_template_regions, R.string.rv_template_regions_line, R.drawable.rv_sym_account_tree),
        listOf(proxy, auto) + regionGroups + listOf(
            telegram,
            service("ai", R.string.rv_template_group_openai),
            service("youtube", R.string.rv_template_group_youtube),
            service("netflix", R.string.rv_template_group_netflix),
            service("bahamut", R.string.rv_template_group_bahamut),
            media,
        ),
    )
    private val homebound = RvConfigTemplate(
        RvTemplate("homebound", R.string.rv_template_homebound, R.string.rv_template_homebound_line, R.drawable.rv_sym_directions),
        listOf(RvTemplateGroup("cn", R.string.rv_template_group_cn, RvTemplatePolicy.Score, "cn")),
    )

    /** doona `primaryTemplates`: the ones that create no group. */
    val primary = listOf(bypass, gfw, global)

    /** doona `moreTemplates`: the ones that create groups. */
    val more = listOf(single, services, regions, homebound)

    /** 依服務分流 over 繞過中國大陸 with the default options: four groups and six rules in, the `proxy` group kept. */
    val apply = RvTemplateApply(
        from = bypass.row,
        to = services.row,
        added = ADDED_RULES,
        removed = 0,
        groups = listOf(auto, telegram, media, apple).map { RvTemplateGroupChange(it, RvChange.Add) } + RvTemplateGroupChange(defaultProxy, RvChange.Keep),
        file = RvDiffFile(RvConfigData.ENTRY, listOf(groupHunk(), routingHunk())),
        baseRev = RvConfigData.REV,
    )

    /** The missing groups go before the group section's closing brace, each under its label (doona `groupLines`). */
    private fun groupHunk(): List<RvDiffLine> {
        val auto = listOf("  # 自動選擇", "  auto {", "    $EVERY_NODE", "    policy: score", "  }")
        val select = { label: String, name: String -> listOf("  # $label", "  $name {", "    filter: group('proxy', 'auto')", "    $EVERY_NODE", "    policy: select", "    default: 'proxy'", "  }") }
        val added = auto + select("Telegram", "telegram") + select("國外媒體", "media") + select("Apple", "apple")
        return listOf(RvDiffLine(Same, 4, "    policy: score"), RvDiffLine(Same, 5, "  }")) +
            added.mapIndexed { i, text -> RvDiffLine(Added, GROUPS_AT + i, text) } +
            RvDiffLine(Same, GROUPS_AT + added.size, "}")
    }

    /** The routing is replaced whole; against 繞過中國大陸 that inserts eleven lines after the mainland games. */
    private fun routingHunk(): List<RvDiffLine> {
        val added = listOf(
            "  # Microsoft",
            "  domain(geosite:microsoft) -> direct",
            "  # Apple",
            "  domain(geosite:apple) -> apple",
            "  # Telegram",
            "  domain(geosite:telegram) -> telegram",
            "  dip(geoip:telegram) -> telegram",
            "  # Overseas media",
            "  domain(geosite:youtube, geosite:netflix, geosite:disney, geosite:hbo, geosite:primevideo, geosite:twitch, geosite:spotify) -> media",
            "  # GFW list",
            "  domain(geosite:gfw) -> proxy",
        )
        return listOf(
            RvDiffLine(Same, ROUTING_AT - 2, "  # Mainland China games"),
            RvDiffLine(Same, ROUTING_AT - 1, "  domain(geosite:category-games@cn) -> direct"),
        ) + added.mapIndexed { i, text -> RvDiffLine(Added, ROUTING_AT + i, text) } + listOf(
            RvDiffLine(Same, ROUTING_AT + added.size, "  # Mainland China"),
            RvDiffLine(Same, ROUTING_AT + added.size + 1, "  domain(geosite:cn) -> direct"),
        )
    }
}

private const val EVERY_NODE = "filter: !name('direct', 'block')"

/** `config.dae` as 繞過中國大陸 writes it: `group { proxy { … } }` on lines 1–6, routing from line 8. */
private const val GROUPS_AT = 6

/** After the 26 group lines, the routing's mainland games rule moves from line 17 to 43. */
private const val ROUTING_AT = 44
private const val ADDED_RULES = 6
