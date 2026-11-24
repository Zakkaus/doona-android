package dev.doona.android.engine

data class ScoreState(val rootBusinessStarts: Long, val groups: List<ScoreGroup>)

/** [nodeIds] lists direct nodes only, even if a subgroup is selected. */
data class ScoreGroup(val id: String, val nodeIds: List<String>, val tcp: ScoreNetwork, val udp: ScoreNetwork)

/** [selectedId] can name a node or a subgroup, or be null if the selection cannot be identified. */
data class ScoreNetwork(
    val selected: String?,
    val selectedId: String?,
    val state: String,
    val waitReason: String,
    val coverage: ScoreCoverage,
    val challengers: List<ScoreChallenger>,
    val counters: ScoreCounters,
)

data class ScoreCoverage(val candidates: Int, val evaluated: Int, val pending: Int)

data class ScoreChallenger(
    val name: String,
    val nodeId: String?,
    val basis: String,
    val relation: String,
    val reporters: Int,
    val validForMs: Long,
)

data class ScoreCounters(val businessStarts: Long, val trialSuccess: Long, val trialFailure: Long, val trialCancelled: Long)
