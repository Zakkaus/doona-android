package dev.doona.android.feature.subscriptions

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaButton
import dev.doona.android.designsystem.components.DoonaButtonStyle
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaMeter
import dev.doona.android.designsystem.components.DoonaSmallStatusLight
import dev.doona.android.designsystem.components.DoonaStatusLight
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.components.Tone
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Subscription
import dev.doona.android.feature.messageRes
import java.text.DateFormat
import java.util.Date

/**
 * One subscription: its name and facts, the traffic meter and the expiry when the provider reports them, the code of
 * its last failed refresh, and the update and remove actions. The update button shows its own busy state.
 */
@Composable
fun SubscriptionCard(item: SubscriptionItem, onUpdate: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val sub = item.subscription
    DoonaCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight)) {
            DoonaText(sub.name, style = DoonaTheme.type.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Facts(item)
        }
        sub.trafficFraction()?.let { fraction ->
            val context = LocalContext.current
            val used = Formatter.formatShortFileSize(context, sub.usedBytes ?: 0)
            val total = Formatter.formatShortFileSize(context, sub.totalBytes ?: 0)
            DoonaMeter(fraction, stringResource(R.string.sub_traffic, used, total), Modifier.fillMaxWidth())
        }
        item.problem?.let { DoonaStatusLight(Tone.Err, stringResource(it.messageRes())) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item), verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block)) {
            DoonaButton(stringResource(R.string.sub_update), onUpdate, style = DoonaButtonStyle.Secondary, busy = item.updating)
            DoonaButton(stringResource(R.string.sub_remove), onRemove, style = DoonaButtonStyle.Text)
        }
    }
}

/** Used over total, when the provider reported both and the total is more than nothing. */
private fun Subscription.trafficFraction(): Float? {
    val used = usedBytes ?: return null
    val total = totalBytes?.takeIf { it > 0 } ?: return null
    return used.toFloat() / total
}

@Composable
private fun Facts(item: SubscriptionItem) {
    val sub = item.subscription
    val date = rememberDateFormat()
    val secondary = DoonaTheme.colors.text.secondary
    val style = DoonaTheme.type.caption
    DoonaText(pluralStringResource(R.plurals.sub_nodes, sub.nodes, sub.nodes), style, color = secondary)
    DoonaText(sub.updatedAtEpochMs?.let { stringResource(R.string.sub_updated, date.format(Date(it))) } ?: stringResource(R.string.sub_never_updated), style, color = secondary)
    sub.expiresAtEpochMs?.let { expiry ->
        if (item.expired) {
            DoonaSmallStatusLight(Tone.Err, stringResource(R.string.sub_expired), style = style, color = secondary)
        } else {
            DoonaText(stringResource(R.string.sub_expires, date.format(Date(expiry))), style, color = secondary)
        }
    }
}

/** The locale's medium date, such as 2026年11月22日; the time of day is left out, since a refresh is a matter of days. */
@Composable
private fun rememberDateFormat(): DateFormat {
    val locale = LocalLocale.current.platformLocale
    return remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
}
