use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::path::{Path, PathBuf};

use honk_config::Config;
use honk_config::diagnostic::{DetailedDiagnostic, Severity};
use honk_config::parser::cursor::{Document, Segment};
use honk_config::parser::lexer::Span;
use honk_config::parser::source_edit::RuleSourceIndex;

use super::{Disposition, Location, Reason, Unsupported, rules};

type Settings = BTreeMap<(&'static str, String), (&'static str, Vec<Location>)>;

#[derive(Default)]
pub(super) struct Declarations {
    settings: Settings,
    nodes: Vec<Location>,
}

pub(super) struct Context<'a> {
    pub config: &'a Config,
    pub root: &'a Path,
    pub links: &'a HashSet<PathBuf>,
    pub diagnostics: &'a [DetailedDiagnostic],
}

impl Declarations {
    pub(super) fn finish(
        self,
        cfg: &Config,
        index: &RuleSourceIndex,
        records: &mut Vec<Unsupported>,
    ) {
        let mut settings = self.settings;
        if let Some((code, mut alias)) =
            settings.remove(&("experimental.udp_nfqueue", "enabled".into()))
        {
            let (_, locations) = settings
                .entry(("global", "nfqueue_enable".into()))
                .or_insert_with(|| (code, Vec::new()));
            locations.append(&mut alias);
            locations.sort_by_key(|loc| (loc.source, loc.bytes.start));
        }
        records.extend(
            settings
                .into_iter()
                .map(|((section, name), (code, locations))| record(section, name, code, locations)),
        );
        // The pin does not export its node-span index. If our locations disagree
        // with admission, omit uncertain coordinates, never an effective node.
        let aligned = self.nodes.len() == cfg.nodes.len();
        let mut locations = self.nodes.into_iter();
        for (i, node) in cfg.nodes.iter().enumerate() {
            let loc = locations.next();
            if node.shadowsocks().is_some_and(unavailable_ss) {
                records.push(record(
                    "node",
                    format!("node[{i}]"),
                    "unavailable_transport",
                    loc.filter(|_| aligned).into_iter().collect(),
                ));
            }
        }
        rules::append(cfg, index, records);
    }

    pub(super) fn collect(&mut self, doc: &Document<'_>, i: usize, ctx: &Context<'_>) {
        // Node::parse_share_link reserves legacy-* for warnings on admitted links.
        let dropped: BTreeSet<_> = ctx
            .diagnostics
            .iter()
            .filter(|d| {
                d.source.index() == i
                    && (d.code == "unterminated-quote"
                        || (d.entry_index.is_some()
                            && d.severity == Severity::Warning
                            && !d.code.starts_with("legacy-")))
            })
            .filter_map(|d| d.span.as_ref().map(|s| s.start))
            .collect();
        for section in doc.sections() {
            match section.header() {
                "node" => node_locations(section, doc, i, &dropped, &mut self.nodes),
                "global" => fields("global", section, doc, i, ctx, &mut self.settings),
                "dns" => fields("dns", section, doc, i, ctx, &mut self.settings),
                "experimental" => {
                    for block in section.body().into_iter().flatten() {
                        let name = match block.header() {
                            "udp_nfqueue" => "experimental.udp_nfqueue",
                            "cache_file" => "experimental.cache_file",
                            "clash_api" => "experimental.clash_api",
                            "native_api" => "experimental.native_api",
                            _ => continue,
                        };
                        fields(name, block, doc, i, ctx, &mut self.settings);
                    }
                }
                _ => {}
            }
        }
    }
}

fn node_locations(
    root: Segment<'_, '_>,
    doc: &Document<'_>,
    i: usize,
    dropped: &BTreeSet<usize>,
    nodes: &mut Vec<Location>,
) {
    let mut stack: Vec<_> = root.body().into_iter().collect();
    while let Some(body) = stack.last_mut() {
        let Some(entry) = body.next() else {
            stack.pop();
            continue;
        };
        if let Some(children) = entry.body() {
            stack.push(children);
            continue;
        }
        let span = entry.header_span();
        if span == entry.span() && dropped.range(span.start..span.end).next().is_none() {
            nodes.push(location(doc, span, i));
        }
    }
}

// honk-outbound's CipherConf::for_method is crate-private. This is a carrier capability
// at the pinned revision, never a share-link admission rule.
fn unavailable_ss(ss: &honk_config::node::ShadowsocksConfig) -> bool {
    const METHODS: &[&str] = &[
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "chacha20-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
    ];
    [ss.plugin.as_deref(), ss.plugin_opts.as_deref()]
        .into_iter()
        .flatten()
        .any(|s| !s.trim().is_empty())
        || ss
            .encryption
            .as_deref()
            .is_some_and(|cipher| !METHODS.iter().any(|c| cipher.eq_ignore_ascii_case(c)))
}

pub(super) fn location(doc: &Document<'_>, span: Span, source: usize) -> Location {
    let (line, byte_column) = doc.source().location(span.start);
    Location {
        source,
        bytes: span.start..span.end,
        line,
        byte_column,
    }
}

pub(super) fn record(
    section: &'static str,
    name: String,
    code: &'static str,
    locations: Vec<Location>,
) -> Unsupported {
    Unsupported {
        section,
        name: name.into(),
        reasons: vec![Reason::new(code)],
        disposition: if code == "include_escape" {
            Disposition::Incomplete
        } else {
            Disposition::Skip
        },
        locations,
    }
}

fn fields(
    section: &'static str,
    block: Segment<'_, '_>,
    doc: &Document<'_>,
    i: usize,
    ctx: &Context<'_>,
    settings: &mut Settings,
) {
    for entry in block.body().into_iter().flatten() {
        if entry.body().is_some() {
            continue;
        }
        let Some((key, value)) = entry.header().split_once(':') else {
            continue;
        };
        let key = key.trim();
        let value = unquote(value.trim());
        let span = entry.header_span();
        if section == "dns" && key == "use_host" {
            host_field(value, ctx, settings, location(doc, span, i));
            continue;
        }
        let Some((active, code)) = reason(section, key, value, ctx.config) else {
            continue;
        };
        let key = (section, key.to_owned());
        if active {
            settings.insert(key, (code, vec![location(doc, span, i)]));
        } else {
            settings.remove(&key);
        }
    }
}

fn unquote(value: &str) -> &str {
    for quote in ['\'', '"'] {
        if let Some(value) = value
            .strip_prefix(quote)
            .and_then(|s| s.strip_suffix(quote))
        {
            return value;
        }
    }
    value
}

fn enabled(value: &str) -> bool {
    ["true", "yes", "1", "on"]
        .iter()
        .any(|s| value.eq_ignore_ascii_case(s))
}

fn host_field(value: &str, ctx: &Context<'_>, settings: &mut Settings, location: Location) {
    let path = if enabled(value) {
        honk_config::dns::SYSTEM_HOSTS_PATH
    } else {
        value
    };
    let Some(i) = ctx.config.dns.hosts.iter().position(|p| p == path) else {
        return;
    };
    let code = match super::staged::confined(ctx.root, Path::new(path), ctx.links) {
        None => "use_host_escape",
        Some(p) if p.is_file() && std::fs::File::open(&p).is_ok() => return,
        Some(_) => "missing_hosts_source",
    };
    settings
        .entry(("dns", format!("use_host[{i}]")))
        .or_insert_with(|| (code, Vec::new()))
        .1
        .push(location);
}

fn reason(section: &str, key: &str, value: &str, cfg: &Config) -> Option<(bool, &'static str)> {
    if section == "global" {
        return global_reason(key, value, &cfg.global);
    }
    let api = &cfg.experimental.clash_api;
    let cache = &cfg.experimental.cache_file;
    Some(match (section, key) {
        ("dns", "bind") => (!cfg.dns.bind.is_empty(), "linux_ingress"),
        ("experimental.udp_nfqueue", "enabled") => (cfg.global.nfqueue_enable, "linux_nfqueue"),
        ("experimental.cache_file", "path") => (
            cache.enabled != Some(false)
                && cache.legacy_cache_file().0.is_some_and(|p| !p.is_empty()),
            "host_path",
        ),
        ("experimental.cache_file", "store_fakeip") => (
            cache.enabled != Some(false) && enabled(value),
            "unsupported_option",
        ),
        ("experimental.clash_api", "default_mode") => (!api.default_mode.is_empty(), "vpn_mode"),
        (
            "experimental.clash_api",
            "external_controller"
            | "external_ui"
            | "external_ui_download_url"
            | "external_ui_download_detour"
            | "secret",
        ) => (
            !api.external_controller.is_empty() && !value.is_empty(),
            "daemon_listener",
        ),
        ("experimental.native_api", "enabled" | "listen" | "secret" | "ui") => (
            cfg.experimental.native_api.enabled && !value.is_empty(),
            "daemon_listener",
        ),
        _ => return None,
    })
}

fn global_reason(
    key: &str,
    value: &str,
    g: &honk_config::config::GlobalConfig,
) -> Option<(bool, &'static str)> {
    Some(match key {
        "tproxy_port" => (g.tproxy_port != 0, "linux_ingress"),
        "tproxy_port_protect" => (g.tproxy_port_protect, "linux_ingress"),
        "so_mark_from_dae" => (g.so_mark_from_dae != 0, "linux_socket_mark"),
        "lan_interface" => (!g.lan_interface.is_empty(), "linux_interface"),
        "wan_interface" => (!g.wan_interface.is_empty(), "linux_interface"),
        "auto_config_kernel_parameter" => (g.auto_config_kernel_parameter, "linux_sysctl"),
        "nfqueue_enable" => (g.nfqueue_enable, "linux_nfqueue"),
        "pprof_port" => (g.pprof_port != 0, "daemon_listener"),
        "disable_waiting_network" => (g.disable_waiting_network, "vpn_lifecycle"),
        "log_file" => (!g.log_file.is_empty(), "host_path"),
        "data_dir" => (!g.data_dir.is_empty(), "host_path"),
        _ => (option_active(key, value, g)?, "unsupported_option"),
    })
}

fn option_active(key: &str, value: &str, g: &honk_config::config::GlobalConfig) -> Option<bool> {
    Some(match key {
        "utls_imitate" => {
            !g.utls_imitate.is_empty() && !g.utls_imitate.trim().starts_with("chrome")
        }
        "mptcp" => g.mptcp,
        "allow_insecure" => g.allow_insecure,
        "tls_fragment" => g.tls_fragment,
        "tls_fragment_length" | "tls_fragment_interval" => g.tls_fragment && !value.is_empty(),
        "fallback_resolver" | "bandwidth_max_tx" | "bandwidth_max_rx" => {
            !value.is_empty() && value != "0"
        }
        _ => return None,
    })
}
