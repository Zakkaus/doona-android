package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvAlpha
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCheckRow
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme

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
 * [label] where web translates it (全部遊戲, 巴哈姆特), its [section] and its row's glyph.
 */
@Immutable
internal class RvService(
    val code: String,
    val name: String,
    val section: RvServiceSection,
    @StringRes val label: Int? = null,
    @DrawableRes val glyph: Int = R.drawable.rv_sym_apps,
)

/** How the sample lists the services, for the owner to compare (owner-fix2 item 14): one card, or web's sections. */
internal enum class RvServiceLayout { Flat, Sections }

/** doona web's service catalogue, section by section, and the sample's choice. */
internal object RvServiceData {
    val all: List<RvService> = listOf(
        games("steam", "Steam"),
        games("epicgames", "Epic Games"),
        games("playstation", "PlayStation"),
        games("xbox", "Xbox"),
        games("nintendo", "Nintendo"),
        games("blizzard", "Blizzard"),
        games("ea", "EA"),
        games("ubisoft", "Ubisoft"),
        games("riot", "Riot Games"),
        RvService("category-games", "All games", RvServiceSection.Games, R.string.rv_service_all_games),
        media("youtube", "YouTube"),
        media("netflix", "Netflix"),
        media("disney", "Disney+"),
        media("hbo", "HBO"),
        media("primevideo", "Prime Video"),
        media("twitch", "Twitch"),
        media("spotify", "Spotify"),
        media("tiktok", "TikTok"),
        RvService("bahamut", "Bahamut", RvServiceSection.Media, R.string.rv_template_group_bahamut),
        media("bilibili", "Bilibili"),
        social("telegram", "Telegram"),
        social("discord", "Discord"),
        social("twitter", "X/Twitter"),
        social("facebook", "Facebook"),
        social("instagram", "Instagram"),
        social("whatsapp", "WhatsApp"),
        social("line", "LINE"),
        RvService("openai", "OpenAI", RvServiceSection.Ai),
        RvService("anthropic", "Anthropic", RvServiceSection.Ai),
        RvService("google-gemini", "Google Gemini", RvServiceSection.Ai),
        RvService("github", "GitHub", RvServiceSection.Developer, glyph = R.drawable.rv_sym_github),
        developer("docker", "Docker"),
        developer("google", "Google"),
        developer("microsoft", "Microsoft"),
        developer("apple", "Apple"),
    )

    /** The services ticked in the sample. */
    val sample: Set<String> = setOf("youtube", "netflix")

    /** The rule already written for [service], as web finds it: a GeoSite rule on the service's code. */
    fun rule(service: RvService, rules: List<RvPolicyRule> = RvPolicyData.allRules): RvPolicyRule? = rules.firstOrNull { (it.match as? RvRuleMatch.GeoSite)?.category == service.code }

    private fun games(code: String, name: String) = RvService(code, name, RvServiceSection.Games)

    private fun media(code: String, name: String) = RvService(code, name, RvServiceSection.Media)

    private fun social(code: String, name: String) = RvService(code, name, RvServiceSection.Social)

    private fun developer(code: String, name: String) = RvService(code, name, RvServiceSection.Developer)
}

/**
 * 新增服務規則, a sample for the owner (owner-fix2 item 14; doona web's ServiceRulesDialog on a phone, without its help
 * line): the large title, the 出站 the new rules send to, then every service as a row to tick, [layout] deciding the
 * list's form: [RvServiceLayout.Flat], one card in web's order, or [RvServiceLayout.Sections], web's five section titles
 * each over a card. A service with a rule already says where that rule sends and cannot be ticked, as on web.
 */
@Composable
internal fun RvServiceRulesPage(
    layout: RvServiceLayout,
    onBack: () -> Unit,
    selected: Set<String> = RvServiceData.sample,
    onToggle: (String) -> Unit = {},
    onOutbound: () -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_service_add)
    val row: @Composable (RvService) -> Unit = { ServiceRow(it, it.code in selected, onToggle) }
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        when (layout) {
            RvServiceLayout.Flat -> RvGutter {
                OutboundCard(onOutbound)
                RvCard { RvServiceData.all.forEach { row(it) } }
            }

            RvServiceLayout.Sections -> {
                RvGutter { OutboundCard(onOutbound) }
                RvServiceSection.entries.forEach { section ->
                    RvSectionTitle(stringResource(section.title), Modifier.padding(horizontal = RvSpace.gutter))
                    RvGutter { RvCard { RvServiceData.all.filter { it.section == section }.forEach { row(it) } } }
                }
            }
        }
    }
}

/** Where the new rules send: the main node, drawn as a rule's target is. */
@Composable
private fun OutboundCard(onOutbound: () -> Unit) {
    val main = RvPolicyData.main
    RvCard {
        RvRow({ RvPolicyTarget(main) }, stringResource(R.string.rv_service_outbound), stringResource(main.name), onClick = onOutbound) { RvChevron() }
    }
}

/** One service to tick; one with a rule already says where it sends, dimmed and not tickable. */
@Composable
private fun ServiceRow(service: RvService, checked: Boolean, onToggle: (String) -> Unit) {
    val rule = RvServiceData.rule(service)
    val title = service.label?.let { stringResource(it) } ?: service.name
    val has = rule?.let { stringResource(R.string.rv_service_has_rule, it.targetName()) }
    val dim = if (rule == null) Modifier else Modifier.graphicsLayer { alpha = RvAlpha.DISABLED }.semantics { disabled() }
    RvCheckRow({ RvIconDisc(service.glyph) }, title, has, checked && rule == null, { if (rule == null) onToggle(service.code) }, dim)
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvServiceRulesPagePreview() {
    val dark = isSystemInDarkTheme()
    var selected by remember { mutableStateOf(RvServiceData.sample) }
    RvTheme(rvSystemScheme(dark), dark) {
        RvServiceRulesPage(RvServiceLayout.Sections, onBack = {}, selected = selected, onToggle = { selected = if (it in selected) selected - it else selected + it })
    }
}
