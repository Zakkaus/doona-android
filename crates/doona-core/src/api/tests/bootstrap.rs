use super::{Core, Mode, TempDir, path, tun};
use crate::api::Options;
use crate::testutil::servers;

#[test]
fn handoff_during_start_wins_after_finish() {
    // Other unit tests construct cores in parallel and replace honk's process-wide resolvers.
    const CHILD: &str = "DOONA_ISOLATED_BOOTSTRAP_TEST";
    if std::env::var_os(CHILD).is_none() {
        let status = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", std::thread::current().name().unwrap()])
            .env(CHILD, "1")
            .status()
            .unwrap();
        assert!(status.success());
        return;
    }
    let (resolver, _) = servers::udp_resolver();
    let options = Options {
        bootstrap_port: resolver.port(),
        bootstrap_fallback: vec![([127, 0, 0, 2], resolver.port()).into()],
        ..Options::default()
    };
    let dir = TempDir::new("dns-during-start");
    let core = Core::with_options(path(&dir), options).unwrap();
    core.set_mode(Mode::Direct).unwrap();
    let lookup = |core: &Core| {
        core.runtime
            .block_on(crate::bootstrap::lookup("handoff.doona.test"))
    };
    core.hooks.on_before_finish(move |core| {
        let reply = core.call("setBootstrapDns", r#"{"servers":["127.0.0.1"]}"#);
        assert_eq!(reply, r#"{"ok":{}}"#);
        assert!(lookup(core).is_ok(), "handoff did not apply during start");
    });
    let (fd, _peer) = tun();
    let config =
        r#"{"mtu":8500,"ipv4":"172.19.0.1","dns":"172.19.0.2","bootstrapDns":["127.0.0.2"]}"#;
    core.start(fd, config).unwrap();
    assert!(lookup(&core).is_ok(), "start overwrote the handoff");
}
