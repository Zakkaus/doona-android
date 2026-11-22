//! The plain Rust API: `init`'s reply, snapshots while stopped, and `call`'s replies and codes.

mod common;

use common::TempDir;
use doonacore::api::Core;
use doonacore::dto::{Empty, ErrorCode, Mode, Reply, Snapshot};

fn snapshot(core: &Core, since: i64) -> Snapshot {
    serde_json::from_str(&core.snapshot(since)).unwrap()
}

fn code(reply: &str) -> Option<ErrorCode> {
    match serde_json::from_str::<Reply<Empty>>(reply).unwrap() {
        Reply::Ok(Empty {}) => None,
        Reply::Error(e) => Some(e.code),
    }
}

#[test]
fn init_replies_with_the_schema() {
    assert_eq!(Core::schema(), r#"{"schema":1}"#);
}

#[test]
fn a_stopped_core_shows_an_empty_catalog_once_per_revision() {
    let dir = TempDir::new("api-catalog");
    let core = Core::new(dir.path().to_str().unwrap()).unwrap();
    let first = snapshot(&core, -1);
    assert!(!first.running && first.error.is_none() && first.generation == 0);
    assert!(first.revision > 0);
    let catalog = first.catalog.expect("a first read gets the catalog");
    assert_eq!(catalog.mode, Mode::Global);
    assert!(catalog.groups.is_empty() && catalog.subscriptions.is_empty());
    assert!(snapshot(&core, first.revision).catalog.is_none());
}

#[test]
fn set_mode_changes_the_revision_only_when_the_mode_changes() {
    let dir = TempDir::new("api-mode");
    let core = Core::new(dir.path().to_str().unwrap()).unwrap();
    let before = snapshot(&core, -1).revision;
    assert_eq!(code(&core.call("setMode", r#"{"mode":"Direct"}"#)), None);
    let after = snapshot(&core, before);
    assert!(after.revision > before);
    assert_eq!(after.catalog.unwrap().mode, Mode::Direct);
    assert_eq!(code(&core.call("setMode", r#"{"mode":"Direct"}"#)), None);
    assert_eq!(snapshot(&core, -1).revision, after.revision);
}

#[test]
fn bad_arguments_and_unknown_commands_are_invalid() {
    let dir = TempDir::new("api-invalid");
    let core = Core::new(dir.path().to_str().unwrap()).unwrap();
    for (command, args) in [
        ("setMode", r#"{"mode":"Rule"}"#),
        ("setMode", "{}"),
        ("setMode", "not json"),
        ("select", r#"{"memberId":"n-1"}"#),
        ("addSubscription", r#"{"name":null}"#),
        ("removeSubscription", "{}"),
        ("reboot", "{}"),
    ] {
        assert_eq!(
            code(&core.call(command, args)),
            Some(ErrorCode::InvalidArgument),
            "{command} {args}"
        );
    }
}

#[test]
fn commands_of_later_lanes_find_nothing() {
    let dir = TempDir::new("api-later");
    let core = Core::new(dir.path().to_str().unwrap()).unwrap();
    let cases = [
        (
            "select",
            r#"{"group":"g-Proxy","memberId":null}"#,
            ErrorCode::NotFound,
        ),
        ("testLatency", r#"{"group":"g-Auto"}"#, ErrorCode::NotFound),
        ("testLatency", r#"{"group":null}"#, ErrorCode::NoNodes),
        (
            "addSubscription",
            r#"{"url":"https://example.invalid/s","name":null}"#,
            ErrorCode::NoNodes,
        ),
        ("updateSubscription", r#"{"id":"s1"}"#, ErrorCode::NotFound),
        ("removeSubscription", r#"{"id":"s1"}"#, ErrorCode::NotFound),
    ];
    for (command, args, expected) in cases {
        assert_eq!(code(&core.call(command, args)), Some(expected), "{command}");
    }
}
