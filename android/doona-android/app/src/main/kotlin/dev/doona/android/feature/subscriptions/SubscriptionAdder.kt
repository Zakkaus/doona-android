package dev.doona.android.feature.subscriptions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The two fields of the add form. A view model holds them so what was typed survives a rotation. */
class AddFormFields(val url: TextFieldState = TextFieldState(), val name: TextFieldState = TextFieldState())

/**
 * What the add form shows besides its fields. [attempted] turns the URL's validation on, so an untouched form is not
 * accused of an empty URL; [busy] is an import running, wherever it was started; [error] is how the last one failed.
 */
@Immutable
data class AddFormState(val attempted: Boolean = false, val busy: Boolean = false, val error: ErrorCode? = null)

/**
 * The logic behind the add form, shared by the subscriptions page and the first-run page: validate, hand the import to
 * [imports], and keep the fields when it fails so the user can correct them. The import and its outcome are the
 * holder's, so they outlast this screen; the adder only follows them. [onImported] runs when an import succeeds, one
 * that was started by an earlier visit included. It is not a ViewModel; the owner's ViewModel hands it its scope.
 */
class SubscriptionAdder(private val imports: SubscriptionImports, scope: CoroutineScope, private val onImported: () -> Unit = {}) {
    val fields = AddFormFields()
    private val attempted = MutableStateFlow(false)
    val state: Flow<AddFormState> = combine(attempted, imports.state) { attempted, import -> AddFormState(attempted, import.busy, import.error) }

    init {
        var handled = imports.state.value.imported
        scope.launch {
            imports.state.collect {
                if (it.imported != handled) {
                    handled = it.imported
                    reset()
                    onImported()
                }
            }
        }
    }

    /** [state] as it is now, for a view model's first frame. */
    fun snapshot() = AddFormState(attempted.value, imports.state.value.busy, imports.state.value.error)

    /** Imports the subscription the fields describe, or marks the URL as attempted when it is not one. While an import runs this does nothing. */
    fun submit() {
        val address = fields.url.text.toString().trim()
        attempted.value = true
        imports.dismissError()
        if (urlProblem(address) == null) imports.start(address, fields.name.text.toString().trim().ifEmpty { null })
    }

    fun dismissError() = imports.dismissError()

    /** Empties the form, as after a successful import or when the user closes it. */
    fun reset() {
        fields.url.clearText()
        fields.name.clearText()
        attempted.value = false
    }
}
