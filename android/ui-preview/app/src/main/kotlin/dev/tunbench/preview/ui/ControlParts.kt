package dev.tunbench.preview.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.tunbench.preview.R
import dev.tunbench.preview.model.ControlState
import java.util.Locale

// Parts the Control and Settings screens share.

/** One label-and-value row. A row with [checked] is a switch that the whole row toggles. */
class RowSpec(
    @param:DrawableRes val icon: Int,
    val label: String,
    val value: String? = null,
    val checked: Boolean? = null,
    val onCheckedChange: (Boolean) -> Unit = {},
)

@Composable
fun parameterRows(state: ControlState): List<RowSpec> = listOf(
    RowSpec(R.drawable.dns_24px, stringResource(R.string.param_upstream), state.parameters.upstream),
    RowSpec(R.drawable.straighten_24px, stringResource(R.string.param_mtu), state.parameters.mtu.toString()),
    RowSpec(
        R.drawable.push_pin_24px,
        stringResource(R.string.param_cpu_pin),
        stringResource(R.string.value_core, state.parameters.cpuCore),
    ),
    RowSpec(
        R.drawable.language_24px,
        stringResource(R.string.param_ipv6),
        checked = state.ipv6,
        onCheckedChange = { state.ipv6 = it },
    ),
)

/** Rows as a segmented list, as in the library's SegmentedListItems sample. */
@Composable
fun SegmentedRows(rows: List<RowSpec>, modifier: Modifier = Modifier) {
    val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, row ->
            val shapes = ListItemDefaults.segmentedShapes(index = index, count = rows.size)
            val leading: @Composable () -> Unit = { Icon(painter = painterResource(row.icon), contentDescription = null) }
            val supporting: (@Composable () -> Unit)? = row.value?.let { { Text(it) } }
            if (row.checked != null) {
                SegmentedListItem(
                    checked = row.checked,
                    onCheckedChange = row.onCheckedChange,
                    shapes = shapes,
                    colors = colors,
                    leadingContent = leading,
                    supportingContent = supporting,
                    trailingContent = { Switch(checked = row.checked, onCheckedChange = null) },
                ) { Text(row.label) }
            } else {
                SegmentedListItem(
                    shapes = shapes,
                    colors = colors,
                    leadingContent = leading,
                    supportingContent = supporting,
                ) { Text(row.label) }
            }
        }
    }
}

/** A number in [style] followed by its unit in [unitStyle]'s size and weight, in the secondary colour. */
@Composable
fun FigureText(
    number: String?,
    unit: String?,
    style: TextStyle,
    unitStyle: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    unitColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val none = stringResource(R.string.value_none)
    val text = buildAnnotatedString {
        append(number ?: none)
        if (number != null && unit != null) {
            withStyle(SpanStyle(fontSize = unitStyle.fontSize, fontWeight = unitStyle.fontWeight, color = unitColor)) {
                append(" ")
                append(unit)
            }
        }
    }
    Text(text = text, modifier = modifier, color = color, style = style.tabular(), maxLines = 1)
}

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

fun twoDecimals(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

fun thousands(value: Int): String = String.format(Locale.ROOT, "%,d", value)

/** A running time as hh:mm:ss. */
fun clock(seconds: Long): String =
    String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
