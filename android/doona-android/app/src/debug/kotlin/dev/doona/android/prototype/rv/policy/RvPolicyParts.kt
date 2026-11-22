package dev.doona.android.prototype.rv.policy

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvChrome
import dev.doona.android.prototype.rv.RvControlSize
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvGlass
import dev.doona.android.prototype.rv.RvGlassButton
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.rvGlass
import dev.doona.android.prototype.rv.rvPress
import dev.doona.android.prototype.rv.rvSoftShadow
import kotlin.math.max
import kotlin.math.min

/** The 策略 pages' own sizes; everything else is the shared tokens. */
internal object RvPolicySize {
    /** The chevron's distance from the column before it. */
    val chevronGap = 4.dp
}

/**
 * Where a rule or a picked target sends traffic, as 活動's rule rows draw it: the flag of the node the group uses, or
 * 直連's straight arrow.
 */
@Composable
internal fun RvPolicyTarget(group: RvPolicyGroup?) {
    if (group == null) RvIconDisc(R.drawable.rv_sym_arrow_right_alt) else RvFlag(group.selected.place.name, RvSize.rowDisc)
}

/**
 * The top bar's actions on the 策略 tab: [RvGlassButton]s at the bar's end, on the row and in the 48 dp targets of
 * [dev.doona.android.prototype.rv.RvTopBar]. Placement only (STATE: give RvScrollPage an actions slot).
 */
@Composable
internal fun RvPolicyBarActions(glass: RvGlass, insets: WindowInsets, actions: List<Triple<Int, String, () -> Unit>>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .windowInsetsPadding(insets.only(WindowInsetsSides.Top))
            .padding(top = RvChrome.barTop - RvChrome.touchPad, end = RvSpace.gutter - RvChrome.touchPad),
        horizontalArrangement = Arrangement.spacedBy(RvChrome.buttonGap - RvChrome.touchPad * 2),
    ) {
        actions.forEach { (icon, label, onClick) -> RvGlassButton(glass, icon, label, onClick) }
    }
}

/**
 * Where a page's one action floats: [RvPrimaryButton] on the 16 dp gutter, 16 dp above the inset, the cards scrolling
 * under it with no scrim.
 */
@Composable
internal fun RvPolicyBottomAction(label: String, onClick: () -> Unit, insets: WindowInsets, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
        RvPrimaryButton(label, onClick)
        Spacer(Modifier.height(RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** The room a page keeps under its last card for its floating action: the gap, the button and its 16 dp over the inset. */
internal val rvPillRoom = RvSpace.gutter + RvControlSize.primary + RvSpace.gutter

/** What a rule matches, as its row's title. */
@Composable
internal fun RvRuleMatch.title(): String = when (this) {
    is RvRuleMatch.Domains -> pluralStringResource(R.plurals.rv_policy_domains, count, first, count)
    is RvRuleMatch.GeoIp -> code
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest)
}

/** The kind of match, under the title. */
@Composable
internal fun RvRuleMatch.kind(): String = when (this) {
    is RvRuleMatch.Domains -> stringResource(R.string.rv_policy_kind_domain)
    is RvRuleMatch.GeoIp -> GEOIP
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan_kind)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest_kind)
}

private const val GEOIP = "GEOIP"

/** The 策略 tab's two bar actions: test every group, update the subscriptions. */
@Composable
internal fun rvPolicyActions(onTestAll: () -> Unit, onUpdate: () -> Unit) = listOf(
    Triple(R.drawable.rv_sym_speed, stringResource(R.string.rv_policy_test_all), onTestAll),
    Triple(R.drawable.rv_stats_ic_sync, stringResource(R.string.rv_policy_update_subscriptions), onUpdate),
)
