package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvImpact
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.RvTemplate
import dev.doona.android.feature.model.rvTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What 範本 shows: where the rules come from now ([source], null until the engine's first catalog), the templates the core
 * lists ([templates], null until read), and the reason the list could not be read ([failure]).
 */
@Immutable
internal data class TemplateUiState(
    val source: RvRuleSource? = null,
    val templates: List<RvTemplate>? = null,
    val failure: ErrorCode? = null,
)

/**
 * 範本: the rule source of each catalog, and the core's templates, read again with each catalog (installing GeoSite data
 * makes one buildable). A failed read keeps the list shown and says so with 重試, which reads again.
 */
internal class TemplateViewModel(private val engine: Engine) : ViewModel() {
    private val read = MutableStateFlow(TemplateUiState())
    private val again = MutableStateFlow(0)

    val uiState: StateFlow<TemplateUiState> = channelFlow {
        launch {
            combine(engine.catalog.filterNotNull(), again) { catalog, _ -> catalog }.collectLatest { catalog ->
                val source = sourceOf(catalog)
                engine.ruleTemplates()
                    .onSuccess { list -> read.value = TemplateUiState(source, list.map { rvTemplate(it.id, it.needsGeosite) }) }
                    .onFailure { error -> read.update { it.copy(source = source, failure = error.errorCode()) } }
            }
        }
        read.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplateUiState(engine.catalog.value?.let(::sourceOf)))

    /** The banner's 重試: reads the templates again. */
    fun retry() {
        again.update { it + 1 }
    }

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { TemplateViewModel(engine) }
        }
    }
}

/** What the template review shows: the review, null until the engine's first catalog, and a failed 套用's reason. */
internal data class TemplateImpactUiState(val impact: RvImpact? = null, val failure: ErrorCode? = null)

/**
 * The review before the template [templateId] replaces the rules, and its 套用. The core tells no impact yet (it has no
 * read for one), so the review names the source and the template and leaves the counts and lists out. 套用 keeps the
 * source's options; once it succeeds, [onApplied] runs, and a failure shows its reason until 套用 succeeds.
 */
internal class TemplateImpactViewModel(private val engine: Engine, private val templateId: String) : ViewModel() {
    private val failure = MutableStateFlow<ErrorCode?>(null)

    val uiState: StateFlow<TemplateImpactUiState> = combine(engine.catalog, failure) { catalog, failed ->
        TemplateImpactUiState(catalog?.let(::impactOf), failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplateImpactUiState(engine.catalog.value?.let(::impactOf)))

    fun apply(onApplied: () -> Unit) {
        val options = engine.catalog.value?.ruleSource?.options ?: return
        viewModelScope.launch {
            engine.setRuleSource(templateId, options)
                .onSuccess {
                    failure.value = null
                    onApplied()
                }
                .onFailure { failure.value = it.errorCode() }
        }
    }

    private fun impactOf(catalog: Catalog) = rvTemplateImpact(sourceOf(catalog), templateId)

    companion object {
        fun factory(engine: Engine, templateId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { TemplateImpactViewModel(engine, templateId) }
        }
    }
}

/** Where [catalog]'s rules come from. */
private fun sourceOf(catalog: Catalog): RvRuleSource = rvRuleSource(catalog.ruleSource, catalog.subscriptions)

/** The review of moving [from] to the template [templateId]: the core tells no counts, rules or groups, so none are listed. */
internal fun rvTemplateImpact(from: RvRuleSource, templateId: String): RvImpact = RvImpact(
    from = from,
    to = rvTemplate(templateId),
    added = null,
    changed = null,
    removed = null,
    kept = null,
    groups = emptyList(),
    rules = emptyList(),
)
