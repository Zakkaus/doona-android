use super::*;
use crate::api::tests_catalog::{Feeds, add, call, path};
use crate::testutil::TempDir;
use honk_outbound::alive::{IpVersion, ProbeDomain};
use honk_outbound::group::{ScoreSelectionContext, ScoreTarget};
use serde_json::{Value, json};
use std::time::Duration;

fn setup(names: &[&str]) -> (TempDir, Core) {
    let dir = TempDir::new("score-state");
    let core = Core::new(path(&dir)).unwrap();
    let feed = Feeds::new();
    let links: Vec<_> = names
        .iter()
        .enumerate()
        .map(|(i, name)| format!("socks5://127.0.0.1:{}#{name}", i + 1))
        .collect();
    feed.set("/sub", links.join("\n").as_bytes());
    add(&core, &feed, "/sub").unwrap();
    (dir, core)
}

fn groups(core: &Core) -> Vec<Value> {
    let mut reply = call(core, "scoreState", json!({})).unwrap();
    serde_json::from_value(reply["groups"].take()).unwrap()
}

fn probes(core: &Core, millis: &[u64]) {
    let c = lock(&core.shared.catalog);
    let model = &c.model;
    let url = "https://health.example/check";
    let context = ScoreSelectionContext {
        target: Some(ScoreTarget::domain("health.example", 443)),
        target_family: Some(IpVersion::V4),
        ..ScoreSelectionContext::aggregate(SelectionNetwork::Tcp, ProbeDomain::Tcp, IpVersion::V4)
    };
    for (entry, &ms) in model.nodes.iter().zip(millis) {
        let feedback = model
            .score
            .manager
            .feedback_for_http_probe(entry.uuid, context.clone(), url, url)
            .unwrap();
        for _ in 0..4 {
            let reporter = feedback.start();
            reporter.probe_latency(Duration::from_millis(ms));
            reporter.finish_setup_only();
        }
    }
}

#[test]
fn no_evidence_waits_for_comparable_traffic() {
    let (_dir, core) = setup(&["a", "b"]);
    for network in ["tcp", "udp"] {
        let auto = &groups(&core)[0][network];
        assert_eq!(auto["state"], "provisional");
        assert_eq!(auto["waitReason"], "comparableTraffic");
        assert_eq!(
            auto["coverage"],
            json!({"candidates":2,"evaluated":2,"pending":2})
        );
        assert!(auto["challengers"].as_array().unwrap().is_empty());
    }
}

#[test]
fn tcp_evidence_changes_only_the_tcp_winner() {
    let (_dir, core) = setup(&["a", "b"]);
    let udp = groups(&core)[0]["udp"]["selected"].clone();
    let (latencies, expected) = if udp == "a" {
        ([500, 10], "b")
    } else {
        ([10, 500], "a")
    };
    probes(&core, &latencies);
    let auto = &groups(&core)[0];
    assert_eq!(auto["tcp"]["selected"], expected);
    assert_eq!(auto["udp"]["selected"], udp);
    assert_ne!(auto["tcp"]["selectedId"], auto["udp"]["selectedId"]);
}

#[test]
fn comparisons_keep_ambiguous_tags_on_the_group() {
    for challenger in ["fast", "other"] {
        let (_dir, core) = setup(&["fast", challenger]);
        probes(&core, &[10, 500]);
        let auto = &groups(&core)[0];
        assert_eq!(auto["tcp"]["selected"], "fast");
        let pair = &auto["tcp"]["challengers"][0];
        assert_eq!(pair["name"], challenger, "{auto}");
        let node_id = if challenger == "fast" {
            &Value::Null
        } else {
            &auto["nodeIds"][1]
        };
        assert_eq!(&pair["nodeId"], node_id);
        assert_eq!(pair["basis"], "configuredProbe");
        assert_eq!(pair["relation"], "selectedFaster");
        assert!(pair["validForMs"].as_u64().unwrap() > 0);
    }
}

#[test]
fn only_score_groups_list_their_direct_node_ids() {
    let (_dir, core) = setup(&["HK01", "other"]);
    let ids = {
        let mut c = lock(&core.shared.catalog);
        c.model
            .groups
            .iter_mut()
            .find(|g| g.id == "g-Streaming")
            .unwrap()
            .kind = GroupKind::UrlTest;
        c.model
            .nodes
            .iter()
            .map(|n| n.id.clone())
            .collect::<Vec<_>>()
    };
    let groups = groups(&core);
    assert_eq!(
        groups.iter().map(|g| &g["id"]).collect::<Vec<_>>(),
        ["g-Auto", "g-HK"]
    );
    assert_eq!(groups[0]["nodeIds"], json!(ids));
    assert_eq!(groups[1]["nodeIds"], json!([ids[0]]));
}

#[test]
fn nested_score_members_keep_their_group_identity() {
    let (_dir, core) = setup(&["HK01", "JP01"]);
    {
        let mut c = lock(&core.shared.catalog);
        let model = &mut c.model;
        model.groups[1].members = vec!["g-HK".into(), "g-JP".into()];
        let index = model
            .nodes
            .iter()
            .enumerate()
            .map(|(i, n)| (n.id.clone(), i))
            .collect();
        let score = Score::new(&model.groups, &model.nodes, &index, Some(&model.score));
        score.publish(Some(&model.score));
        model.score = Arc::new(score);
    }
    probes(&core, &[10, 500]);
    let auto = &groups(&core)[0];
    assert_eq!(auto["nodeIds"], json!([]));
    let tcp = &auto["tcp"];
    assert_eq!(tcp["selected"], "g-HK");
    assert_eq!(tcp["selectedId"], "g-HK");
    let challenger = &tcp["challengers"][0];
    assert_eq!(challenger["name"], "g-JP");
    assert_eq!(challenger.get("nodeId"), Some(&Value::Null));
}
