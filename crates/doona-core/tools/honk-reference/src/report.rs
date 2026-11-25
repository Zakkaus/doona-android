//! The versioned report and its comparison with a stored one.

use serde::Serialize;
use serde_json::Value;

use crate::assets::Asset;
use crate::run::CaseReport;

pub const REPORT_VERSION: u64 = 1;

#[derive(Serialize)]
pub struct Report {
    pub version: u64,
    /// The honk revision this binary links (Cargo.toml).
    pub honk_rev: &'static str,
    pub geo: Vec<Asset>,
    pub cases: Vec<CaseReport>,
}

/// Every place where `actual` differs from `expected`, as `path: expected X, got Y`.
pub fn diff(expected: &Value, actual: &Value) -> Vec<String> {
    let mut out = Vec::new();
    walk("$", expected, actual, &mut out);
    out
}

fn walk(path: &str, expected: &Value, actual: &Value, out: &mut Vec<String>) {
    match (expected, actual) {
        (Value::Object(want), Value::Object(got)) => {
            for (key, value) in want {
                match got.get(key) {
                    Some(other) => walk(&format!("{path}.{key}"), value, other, out),
                    None => out.push(format!("{path}.{key}: expected {value}, missing")),
                }
            }
            for key in got.keys().filter(|key| !want.contains_key(*key)) {
                out.push(format!("{path}.{key}: not expected"));
            }
        }
        (Value::Array(want), Value::Array(got)) => {
            for (index, (a, b)) in want.iter().zip(got).enumerate() {
                walk(&format!("{path}[{index}]"), a, b, out);
            }
            if want.len() != got.len() {
                out.push(format!(
                    "{path}: expected {} items, got {}",
                    want.len(),
                    got.len()
                ));
            }
        }
        _ if expected == actual => {}
        _ => out.push(format!("{path}: expected {expected}, got {actual}")),
    }
}
