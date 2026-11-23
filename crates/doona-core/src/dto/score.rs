//! `scoreState` uses aggregate honk verification, not a pin or a flow's chosen leaf.

use honk_outbound::group::{
    ScoreEvidenceBasis, ScoreRelation, ScoreVerificationState, ScoreWaitReason,
};
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScoreState<Id = String> {
    pub root_business_starts: u64,
    pub groups: Vec<Group<Id>>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Group<Id> {
    pub id: Id,
    pub node_ids: Vec<Id>,
    pub tcp: Network<Id>,
    pub udp: Network<Id>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Network<Id> {
    pub selected: Option<String>,
    pub selected_id: Option<Id>,
    #[serde(with = "State")]
    pub state: ScoreVerificationState,
    #[serde(with = "WaitReason")]
    pub wait_reason: ScoreWaitReason,
    pub coverage: Coverage,
    pub challengers: Vec<Challenger<Id>>,
    pub counters: Counters,
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct Coverage {
    pub candidates: usize,
    pub evaluated: usize,
    pub pending: usize,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Challenger<Id> {
    pub name: String,
    pub node_id: Option<Id>,
    #[serde(with = "Basis")]
    pub basis: ScoreEvidenceBasis,
    #[serde(with = "Relation")]
    pub relation: ScoreRelation,
    pub reporters: u8,
    pub valid_for_ms: u64,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Counters {
    pub business_starts: u64,
    pub trial_success: u64,
    pub trial_failure: u64,
    pub trial_cancelled: u64,
}

#[derive(Serialize, Deserialize)]
#[serde(remote = "ScoreVerificationState", rename_all = "camelCase")]
enum State {
    Provisional,
    ObservedUsable,
}

#[derive(Serialize, Deserialize)]
#[serde(remote = "ScoreWaitReason", rename_all = "camelCase")]
enum WaitReason {
    None,
    Budget,
    ComparableTraffic,
    InFlight,
    Backoff,
}

#[derive(Serialize, Deserialize)]
#[serde(remote = "ScoreEvidenceBasis", rename_all = "camelCase")]
enum Basis {
    ConfiguredProbe,
    TargetResponse,
    CommonTargets,
}

#[derive(Serialize, Deserialize)]
#[serde(remote = "ScoreRelation", rename_all = "camelCase")]
enum Relation {
    SelectedFaster,
    Equivalent,
    ChallengerFaster,
}
