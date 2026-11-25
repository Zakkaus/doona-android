package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.doona.android.R

/**
 * A sheet that sets one typed value: [title], which also names the field, one field holding [text] with its
 * [placeholder] and the [hint] under it, or the [error] once a save failed, and 儲存 (once something is typed) and 取消.
 * Done on the keyboard saves too. 設定's 自訂 rows (DNS, 更新間隔) open one.
 */
@Composable
internal fun RvInputSheet(
    open: Boolean,
    title: String,
    text: String,
    onText: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    placeholder: String,
    hint: String,
    error: String?,
    keyboardType: KeyboardType,
) {
    RvSheet(open, onDismiss, title) { RvInputContent(title, text, onText, onSave, onDismiss, placeholder, hint, error, keyboardType) }
}

/** What [RvInputSheet] shows in its card. */
@Composable
internal fun ColumnScope.RvInputContent(
    title: String,
    text: String,
    onText: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    placeholder: String,
    hint: String,
    error: String?,
    keyboardType: KeyboardType,
) {
    RvConfirmContent(
        title = title,
        body = null,
        confirm = stringResource(R.string.rv_save),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = onSave,
        onDismiss = onDismiss,
        confirmEnabled = text.isNotBlank(),
    ) {
        RvTextField(
            text,
            onText,
            label = null,
            name = title,
            placeholder = placeholder,
            supporting = hint,
            error = error,
            keyboard = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            actions = KeyboardActions(onDone = { onSave() }),
        )
    }
}
