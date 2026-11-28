package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvImpact
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.RvTemplate
import dev.doona.android.feature.model.rvTemplate
import dev.doona.android.honk.HonkSource
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeConfig
import dev.doona.android.honk.daeChildren
import dev.doona.android.honk.geoData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What 範本 shows: where the rules come from now ([source]) and doona's templates ([templates]), both null until honk's
 * config and geodata are read, and the reason they could not be read ([failure]).
 */
@Immutable
internal data class TemplateUiState(
    val source: RvRuleSource? = null,
    val templates: List<RvTemplate>? = null,
    val failure: ErrorCode? = null,
)

/**
 * 範本: doona's templates, the one honk's routing is, and which need the GeoSite file honk has not loaded, read from
 * honk's config and geodata on [io] at start and on 重試. A failed read keeps the list shown and says so with 重試.
 */
internal class TemplateViewModel(private val api: NativeApi, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val state = MutableStateFlow(TemplateUiState())
    private var reading: Job? = null

    val uiState: StateFlow<TemplateUiState> = state.asStateFlow()

    init {
        load()
    }

    /** The banner's 重試: reads again. */
    fun retry() = load()

    private fun load() {
        reading?.cancel()
        reading = viewModelScope.launch {
            try {
                val (config, geosite) = withContext(io) { NativeConfig(api).config() to loadsGeosite() }
                state.value = TemplateUiState(honkRuleSource(HonkRouting.of(config)), HonkTemplates.listed(geosite))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(failure = error.errorCode()) }
            }
        }
    }

    /** Whether honk loaded a GeoSite file; honk with no geodata at all (404) loaded none. */
    private fun loadsGeosite(): Boolean = try {
        api.geoData().assets.any { it.kind == GEOSITE }
    } catch (error: EngineException) {
        if (error.code != ErrorCode.Unsupported) throw error
        false
    }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { TemplateViewModel(api) }
        }
    }
}

/** What the template review shows: the review, null until honk's config is read, and a failed 套用's reason. */
internal data class TemplateImpactUiState(val impact: RvImpact? = null, val failure: ErrorCode? = null, val edit: Pair<HonkSource, String>? = null)

/**
 * The review before the template [templateId] replaces the rules, and its 套用. honk supplies no impact, so the review
 * shows unknown counts and details. Applying uses the reviewed source hash; a conflict requires a fresh review.
 */
internal class TemplateImpactViewModel(
    private val api: NativeApi,
    private val templateId: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val state = MutableStateFlow(TemplateImpactUiState())
    private var applying: Job? = null

    val uiState: StateFlow<TemplateImpactUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                state.value = withContext(io) {
                    val config = NativeConfig(api).config()
                    val routing = HonkRouting.of(config)
                    TemplateImpactUiState(
                        rvTemplateImpact(honkRuleSource(routing), templateId),
                        edit = routing?.let {
                            it.source to it.withTemplate(templateId, config.sources.flatMap { source -> daeChildren(source.content, "group") }.mapTo(mutableSetOf()) { group -> group.first })
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(failure = error.errorCode()) }
            }
        }
    }

    /** 套用, once the review is read; a tap while one runs does nothing. */
    fun apply(onApplied: () -> Unit) {
        val review = state.value
        val (source, candidate) = review.edit ?: return
        if (!source.writable) return
        if (applying?.isActive == true) return
        applying = viewModelScope.launch {
            try {
                val landed = withContext(io) {
                    val config = NativeConfig(api)
                    if (review.failure != null) {
                        val current = config.config().sources.firstOrNull { it.id == source.id }
                        if (current?.content == candidate) return@withContext true
                        if (current?.sha256 != source.sha256) return@withContext false
                    }
                    config.replace(source, candidate)
                    true
                }
                if (!landed) return@launch load()
                state.update { it.copy(failure = null) }
                onApplied()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(failure = error.errorCode()) }
            }
        }
    }

    companion object {
        fun factory(api: NativeApi, templateId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { TemplateImpactViewModel(api, templateId) }
        }
    }
}

private const val GEOSITE = "geosite"

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
