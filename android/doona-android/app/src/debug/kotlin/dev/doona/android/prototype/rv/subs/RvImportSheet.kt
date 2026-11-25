package dev.doona.android.prototype.rv.subs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvConfirmSheet

/**
 * The deep link's question (`clash://install-config?url=…&name=…`, or a link shared to doona): the subscription's name
 * and the host it comes from, 加入 or 取消. Nothing is fetched before 加入.
 */
@Composable
internal fun RvImportSheet(visible: Boolean, name: String, host: String, onAdd: () -> Unit, onDismiss: () -> Unit) {
    RvConfirmSheet(
        visible,
        stringResource(R.string.rv_import_title, name),
        stringResource(R.string.rv_import_body, host),
        stringResource(R.string.rv_import_add),
        stringResource(R.string.rv_cancel),
        onAdd,
        onDismiss,
        titleKeepWords = false,
        bodyKeepWords = false,
    )
}
