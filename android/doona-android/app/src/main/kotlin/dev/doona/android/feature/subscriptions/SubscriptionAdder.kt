package dev.doona.android.feature.subscriptions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Immutable
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The two fields of the add form. A view model holds them so what was typed survives a rotation. */
class AddFormFields(val url: TextFieldState = TextFieldState(), val name: TextFieldState = TextFieldState())

/**
 * What the add form shows besides its fields. [attempted] turns the URL's validation on, so an untouched form is not
 * accused of an empty URL; [busy] is the time between the tap and the engine's answer; [error] is that answer when it
 * failed.
 */
@Immutable
data class AddFormState(val attempted: Boolean = false, val busy: Boolean = false, val error: ErrorCode? = null)

/**
 * The logic behind the add form, shared by the subscriptions page and the first-run page: validate, add, and keep the
 * fields when the add fails so the user can correct them. It is not a ViewModel; the owner's ViewModel hands it its scope.
 */
class SubscriptionAdder(private val engine: Engine, private val scope: CoroutineScope) {
    val fields = AddFormFields()
    private val form = MutableStateFlow(AddFormState())
    val state: StateFlow<AddFormState> = form

    /** Adds the subscription the fields describe; [onAdded] runs once the engine has taken it. */
    fun submit(onAdded: () -> Unit = {}) {
        if (form.value.busy) return
        val address = fields.url.text.toString().trim()
        if (urlProblem(address) != null) {
            form.value = AddFormState(attempted = true)
            return
        }
        form.value = AddFormState(attempted = true, busy = true)
        val name = fields.name.text.toString().trim().ifEmpty { null }
        scope.launch {
            engine.addSubscription(address, name).fold(
                onSuccess = {
                    reset()
                    onAdded()
                },
                onFailure = { e -> form.update { it.copy(busy = false, error = e.errorCode()) } },
            )
        }
    }

    fun dismissError() = form.update { it.copy(error = null) }

    /** Empties the form, as after a successful add or when the user closes it. */
    fun reset() {
        fields.url.clearText()
        fields.name.clearText()
        form.value = AddFormState()
    }
}
