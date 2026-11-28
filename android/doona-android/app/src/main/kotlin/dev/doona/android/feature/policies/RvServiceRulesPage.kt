package dev.doona.android.feature.policies

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RvAlpha
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCheckRow
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.text

/** doona web's sections of the service list (`serviceSections` in `src/dae/templates.ts`), in its order. */
internal enum class RvServiceSection(@StringRes val title: Int) {
    Games(R.string.rv_service_games),
    Media(R.string.rv_service_media),
    Social(R.string.rv_service_social),
    Ai(R.string.rv_rule_ai),
    Developer(R.string.rv_service_developer),
}

/**
 * A service doona web can write a rule for: its GeoSite [code], its [name] (a brand, the same in every language) or a
 * [label] where web translates it (全部遊戲, 巴哈姆特), its [section], and its row's [glyph]: the brand's mark
 * (`rv_brand_*` from Simple Icons, Bootstrap Icons or Wikimedia Commons, see `app/src/main/BRAND_ICONS.md`; GitHub's
 * `rv_sym_github`), or the generic apps glyph for a service no source has a mark for.
 */
@Immutable
internal class RvService(
    val code: String,
    val name: String,
    val section: RvServiceSection,
    @StringRes val label: Int? = null,
    @DrawableRes val glyph: Int = R.drawable.rv_sym_apps,
)

/** doona web's service catalogue, section by section. */
internal object RvServiceData {
    val all: List<RvService> = listOf(
        games("steam", "Steam", R.drawable.rv_brand_steam),
        games("epicgames", "Epic Games", R.drawable.rv_brand_epicgames),
        games("playstation", "PlayStation", R.drawable.rv_brand_playstation),
        games("xbox", "Xbox", R.drawable.rv_brand_xbox),
        games("nintendo", "Nintendo", R.drawable.rv_brand_nintendo),
        games("blizzard", "Blizzard", R.drawable.rv_brand_battledotnet),
        games("ea", "EA", R.drawable.rv_brand_ea),
        games("ubisoft", "Ubisoft", R.drawable.rv_brand_ubisoft),
        games("riot", "Riot Games", R.drawable.rv_brand_riotgames),
        RvService("category-games", "All games", RvServiceSection.Games, R.string.rv_service_all_games),
        media("youtube", "YouTube", R.drawable.rv_brand_youtube),
        media("netflix", "Netflix", R.drawable.rv_brand_netflix),
        media("disney", "Disney+", R.drawable.rv_brand_disneyplus),
        media("hbo", "HBO", R.drawable.rv_brand_hbo),
        media("primevideo", "Prime Video", R.drawable.rv_brand_primevideo),
        media("twitch", "Twitch", R.drawable.rv_brand_twitch),
        media("spotify", "Spotify", R.drawable.rv_brand_spotify),
        media("tiktok", "TikTok", R.drawable.rv_brand_tiktok),
        RvService("bahamut", "Bahamut", RvServiceSection.Media, R.string.rv_template_group_bahamut),
        media("bilibili", "Bilibili", R.drawable.rv_brand_bilibili),
        social("telegram", "Telegram", R.drawable.rv_brand_telegram),
        social("discord", "Discord", R.drawable.rv_brand_discord),
        social("twitter", "X/Twitter", R.drawable.rv_brand_x),
        social("facebook", "Facebook", R.drawable.rv_brand_facebook),
        social("instagram", "Instagram", R.drawable.rv_brand_instagram),
        social("whatsapp", "WhatsApp", R.drawable.rv_brand_whatsapp),
        social("line", "LINE", R.drawable.rv_brand_line),
        RvService("openai", "OpenAI", RvServiceSection.Ai, glyph = R.drawable.rv_brand_openai),
        RvService("anthropic", "Anthropic", RvServiceSection.Ai, glyph = R.drawable.rv_brand_anthropic),
        RvService("google-gemini", "Google Gemini", RvServiceSection.Ai, glyph = R.drawable.rv_brand_googlegemini),
        developer("github", "GitHub", R.drawable.rv_sym_github),
        developer("docker", "Docker", R.drawable.rv_brand_docker),
        developer("google", "Google", R.drawable.rv_brand_google),
        developer("microsoft", "Microsoft", R.drawable.rv_brand_microsoft),
        developer("apple", "Apple", R.drawable.rv_brand_apple),
    )

    private fun games(code: String, name: String, @DrawableRes glyph: Int = R.drawable.rv_sym_apps) = RvService(code, name, RvServiceSection.Games, glyph = glyph)

    private fun media(code: String, name: String, @DrawableRes glyph: Int = R.drawable.rv_sym_apps) = RvService(code, name, RvServiceSection.Media, glyph = glyph)

    private fun social(code: String, name: String, @DrawableRes glyph: Int = R.drawable.rv_sym_apps) = RvService(code, name, RvServiceSection.Social, glyph = glyph)

    private fun developer(code: String, name: String, @DrawableRes glyph: Int = R.drawable.rv_sym_apps) = RvService(code, name, RvServiceSection.Developer, glyph = glyph)
}

/** The rule already written for each service, by code, as web finds it: the first GeoSite rule on the service's code. */
internal fun rvServiceRules(rules: List<RvPolicyRule>): Map<String, RvPolicyRule> = RvServiceData.all.mapNotNull { service ->
    rules.firstOrNull { (it.match as? RvRuleMatch.GeoSite)?.category == service.code }?.let { service.code to it }
}.toMap()

/**
 * 新增服務規則 (owner-fix2 item 14, the owner's B; doona web's ServiceRulesDialog on a phone, without its help line): the
 * large title, where the new rules go out (出站, the owner's word; owner-fix4 item 30: the [main] group), then web's five
 * sections, each a title over a card of services to tick, each row with its brand's mark in the row glyph's disc and tint
 * (the generic glyph where there is none). A service with a rule already ([rules], by code) says where that rule sends and
 * cannot be ticked, as on web. With [onApply], 加入並套用 floats at the bottom, on once a service is ticked; a [banner]
 * (a write honk did not take) heads the page.
 */
@Composable
internal fun RvServiceRulesPage(
    main: RvPolicyGroup?,
    rules: Map<String, RvPolicyRule>,
    selected: Set<String>,
    onBack: () -> Unit,
    onToggle: (String) -> Unit = {},
    onApply: (() -> Unit)? = null,
    banner: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_service_add)
    val row: @Composable (RvService) -> Unit = { ServiceRow(it, rules[it.code], it.code in selected, onToggle) }
    val ticked = selected.any { it !in rules }
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = onApply?.let { apply -> { RvPrimaryButton(stringResource(R.string.rv_service_apply), apply, enabled = ticked && main != null) } },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter(banner = banner) { OutboundCard(main) }
        RvServiceSection.entries.forEach { section ->
            RvSectionTitle(stringResource(section.title), Modifier.padding(horizontal = RvSpace.gutter))
            RvGutter { RvCard { RvServiceData.all.filter { it.section == section }.forEach { row(it) } } }
        }
    }
}

/** Where the new rules send: the main node, drawn as a rule's target is. */
@Composable
private fun OutboundCard(main: RvPolicyGroup?) {
    RvCard {
        RvRow({ RvPolicyTarget(main) }, stringResource(R.string.rv_service_outbound), main?.name?.text() ?: RV_NO_VALUE, subtitleKeepWords = false)
    }
}

/** One service to tick; one with a [rule] already says where it sends, dimmed and not tickable. */
@Composable
private fun ServiceRow(service: RvService, rule: RvPolicyRule?, checked: Boolean, onToggle: (String) -> Unit) {
    val title = service.label?.let { stringResource(it) } ?: service.name
    val has = rule?.let { stringResource(R.string.rv_service_has_rule, it.targetName()) }
    val dim = if (rule == null) Modifier else Modifier.graphicsLayer { alpha = RvAlpha.DISABLED }
    RvCheckRow({ RvIconDisc(service.glyph) }, title, has, checked && rule == null, { onToggle(service.code) }, dim, enabled = rule == null, subtitleKeepWords = false)
}
