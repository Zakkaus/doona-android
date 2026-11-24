package dev.doona.android.vpn

import dev.doona.android.engine.ScoreChallenger
import dev.doona.android.engine.ScoreCounters
import dev.doona.android.engine.ScoreCoverage
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.engine.ScoreNetwork
import dev.doona.android.engine.ScoreState
import kotlinx.serialization.Serializable

@Serializable
internal data class ScoreStateDto(val rootBusinessStarts: Long, val groups: List<ScoreGroupDto>) {
    fun toScoreState() = ScoreState(rootBusinessStarts, groups.map(ScoreGroupDto::toScoreGroup))
}

@Serializable
internal data class ScoreGroupDto(val id: String, val nodeIds: List<String>, val tcp: ScoreNetworkDto, val udp: ScoreNetworkDto) {
    fun toScoreGroup() = ScoreGroup(id, nodeIds, tcp.toScoreNetwork(), udp.toScoreNetwork())
}

@Serializable
internal data class ScoreNetworkDto(
    val selected: String? = null,
    val selectedId: String? = null,
    val state: String,
    val waitReason: String,
    val coverage: ScoreCoverageDto,
    val challengers: List<ScoreChallengerDto>,
    val counters: ScoreCountersDto,
) {
    fun toScoreNetwork() = ScoreNetwork(
        selected,
        selectedId,
        state,
        waitReason,
        coverage.toScoreCoverage(),
        challengers.map(ScoreChallengerDto::toScoreChallenger),
        counters.toScoreCounters(),
    )
}

@Serializable
internal data class ScoreCoverageDto(val candidates: Int, val evaluated: Int, val pending: Int) {
    fun toScoreCoverage() = ScoreCoverage(candidates, evaluated, pending)
}

@Serializable
internal data class ScoreChallengerDto(
    val name: String,
    val nodeId: String? = null,
    val basis: String,
    val relation: String,
    val reporters: Int,
    val validForMs: Long,
) {
    fun toScoreChallenger() = ScoreChallenger(name, nodeId, basis, relation, reporters, validForMs)
}

@Serializable
internal data class ScoreCountersDto(val businessStarts: Long, val trialSuccess: Long, val trialFailure: Long, val trialCancelled: Long) {
    fun toScoreCounters() = ScoreCounters(businessStarts, trialSuccess, trialFailure, trialCancelled)
}
