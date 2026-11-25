//! Exports the honk revision named in Cargo.toml, so the report states the revision it was built from.

fn main() {
    println!("cargo:rerun-if-changed=Cargo.toml");
    let manifest = std::fs::read_to_string("Cargo.toml").expect("read Cargo.toml");
    let line = manifest
        .lines()
        .find(|line| line.starts_with("honk-core = "))
        .expect("honk-core dependency");
    let rev = line
        .split("rev = \"")
        .nth(1)
        .and_then(|rest| rest.split('"').next())
        .expect("honk-core rev");
    println!("cargo:rustc-env=HONK_PIN={rev}");
}
