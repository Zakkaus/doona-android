//! Exports the honk revision named in Cargo.toml, so the report states the revision it was built from.

fn rev_of(manifest: &str, dependency: &str) -> String {
    let line = manifest
        .lines()
        .find(|line| line.starts_with(dependency))
        .unwrap_or_else(|| panic!("{dependency} dependency"));
    line.split("rev = \"")
        .nth(1)
        .and_then(|rest| rest.split('"').next())
        .unwrap_or_else(|| panic!("{dependency} rev"))
        .to_owned()
}

fn main() {
    println!("cargo:rerun-if-changed=Cargo.toml");
    let manifest = std::fs::read_to_string("Cargo.toml").expect("read Cargo.toml");
    let core = rev_of(&manifest, "honk-core = ");
    // honk-config parses the corpus, so the one revision in the report must cover it too.
    assert_eq!(
        core,
        rev_of(&manifest, "honk-config = "),
        "honk-config and honk-core pin different revisions"
    );
    println!("cargo:rustc-env=HONK_PIN={core}");
}
