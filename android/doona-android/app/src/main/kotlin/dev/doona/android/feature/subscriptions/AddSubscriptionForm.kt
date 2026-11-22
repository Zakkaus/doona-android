package dev.doona.android.feature.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaButton
import dev.doona.android.designsystem.components.DoonaButtonStyle
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.components.DoonaTextField
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.feature.messageRes

private val UrlKeyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
private val NameKeyboard = KeyboardOptions(imeAction = ImeAction.Done)

/**
 * The form that adds a subscription: the URL, an optional name and the add button, in a card, with the engine's error
 * in a banner above it. It is stateless: [fields] hold the text, [state] the rest. A URL that is empty or not
 * http(s) is named under its field once the user has tried to add. With [onCancel] the form can be closed; the
 * first-run page, which has nothing behind it, passes none.
 */
@Composable
fun AddSubscriptionForm(
    fields: AddFormFields,
    state: AddFormState,
    onSubmit: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val problem = if (state.attempted) urlProblem(fields.url.text.toString()) else null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block)) {
        state.error?.let { code ->
            DoonaErrorBanner(stringResource(code.messageRes()), stringResource(R.string.ui_dismiss), onDismissError)
        }
        DoonaCard {
            DoonaTextField(
                fields.url,
                label = stringResource(R.string.sub_url),
                error = problem?.let { stringResource(it.messageRes()) },
                enabled = !state.busy,
                keyboardOptions = UrlKeyboard,
            )
            DoonaTextField(
                fields.name,
                label = stringResource(R.string.sub_name),
                enabled = !state.busy,
                keyboardOptions = NameKeyboard,
                onImeAction = onSubmit,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item), verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block)) {
                DoonaButton(stringResource(R.string.sub_add_submit), onSubmit, busy = state.busy)
                if (onCancel != null) {
                    DoonaButton(stringResource(R.string.ui_cancel), onCancel, style = DoonaButtonStyle.Text, enabled = !state.busy)
                }
            }
        }
    }
}

private fun UrlProblem.messageRes(): Int = when (this) {
    UrlProblem.Empty -> R.string.sub_url_empty
    UrlProblem.Invalid -> R.string.sub_url_invalid
}
