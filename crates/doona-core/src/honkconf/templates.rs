//! The starting configurations the app offers, in the order the 範本 page lists them. Each is
//! plain honk text that becomes the user's entry file; the app reads it like any other source,
//! so nothing here names a group or a rule.

/// One starting configuration: its stable ID and the entry file's text.
pub struct Template {
    pub id: &'static str,
    pub source: &'static str,
}

/// `cn-direct` comes first: it is the seed a new configuration starts from.
pub const TEMPLATES: [Template; 5] = [
    Template {
        id: "cn-direct",
        source: include_str!("../../templates/cn-direct.dae"),
    },
    Template {
        id: "gfw",
        source: include_str!("../../templates/gfw.dae"),
    },
    Template {
        id: "services",
        source: include_str!("../../templates/services.dae"),
    },
    Template {
        id: "regions",
        source: include_str!("../../templates/regions.dae"),
    },
    Template {
        id: "homebound",
        source: include_str!("../../templates/homebound.dae"),
    },
];
