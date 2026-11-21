# Agent guide

honk-android holds honk's Android VpnService integration crate (`crates/honk-android`), the
TunBench benchmark app (`android/tunbench`), the bench scripts and plans (`android/scripts`,
`android/plans`) and the harness design (`doc/{en,zh}/design/android-bench.md`). Start with
[`README.md`](README.md) and [`android/scripts/README.md`](android/scripts/README.md).

## Layout

```
.
├── crates/honk-android/   # Rust crate -> libhonkengine.so; standalone, own Cargo.lock, toolchain file, release profile
├── android/tunbench/      # Gradle app dev.tunbench; hev-socks5-tunnel submodule under app/src/main/jni/
├── android/scripts/       # run.sh, analyze.py (uv project), build-netstack-bench.sh, android-env.sh, README
├── android/plans/         # bench plans: smoke.json, compare.json, compare-v2.json
├── doc/en, doc/zh         # design/android-bench.md in both languages
└── .github/workflows/     # android.yml: CI for the Rust, Android and script checks
```

## Toolchain

On the maintainer's desktop, `source ~/.cache/android-dev/env.sh` before building. It sets
`JAVA_HOME`, `ANDROID_HOME`, `RUSTUP_HOME`, `CARGO_HOME` and the uv variables, and exports
`CARGO_TARGET_DIR`; override that per build where a step needs its own target directory.
Elsewhere, install the prerequisites in `android/scripts/README.md`.

## Gates

Run these before every push. CI runs all of them except `shfmt`.

In `crates/honk-android`:

```sh
cargo fmt --check
cargo clippy --locked --all-targets -- -D warnings
cargo clippy --locked --all-targets --features bench -- -D warnings
cargo test --locked --features bench
cargo test --locked --release --test relay
cargo ndk -t arm64-v8a --platform 29 clippy --locked --all-targets --features bench -- -D warnings
```

In `android/tunbench`: `./gradlew assembleDebug lint`, then `zipalign -c -P 16 4` on
`app/build/outputs/apk/debug/app-debug.apk` and `llvm-readelf -lW` on both `.so` files: every
`LOAD` segment must be aligned to `0x4000`.

In `android/scripts`: `uv run ruff check`, `uv run ruff format --check`, `uv run pytest`,
`shellcheck run.sh` and `shfmt -d run.sh`.

## Build invariants

- `crates/honk-android` is its own Cargo workspace root. Gradle runs cargo in that directory, so
  its `rust-toolchain.toml`, release profile (`opt-level = 3`, `codegen-units = 1`, `strip`;
  `lto = "fat"`, which applies because Gradle builds the `cdylib` alone with `cargo rustc --crate-type cdylib`), `Cargo.lock` and `.cargo/config.toml` (the 16 KiB page linker flag) all apply. Do not
  add a root workspace or move these files.
- `rust-toolchain.toml` pins the same compiler as honk. Change it together with honk.
- Do not run `cargo update` as a side effect. Change a dependency version in its own commit.
- honk-netstack is a Git dependency on
  [Glassyiris/honk](https://github.com/Glassyiris/honk) `c5b36a64`, the head of
  [daeuniverse/honk#483](https://github.com/daeuniverse/honk/pull/483). The pin breaks if that
  fork is deleted or the commit is lost to a force-push. After #483 merges, point the dependency
  at `daeuniverse/honk` and update the honk links in the docs.

## Netstack bench

`android/scripts/build-netstack-bench.sh` builds honk-netstack's `tcp_bulk` example and `stack`
bench in a honk checkout. Set `HONK_DIR` to that checkout; the known-good revision is
Glassyiris/honk `5d0a900d` (`feat/android-bench`), because `tcp_bulk` is not in `c5b36a64`. Run
it with `CARGO_TARGET_DIR="$HONK_DIR/target"`: the script copies the binaries from there, and
the desktop's `~/.cache/android-dev/env.sh` points `CARGO_TARGET_DIR` elsewhere.

## Phone rules

- Do not run an engine comparison (`android/plans/compare*.json`, or any hev against honk run)
  without the maintainer's approval.
- When no phone test is running, leave `svc power stayon false` and the screen allowed to sleep.
  A step that sets `stayon` must restore it in a trap on EXIT, INT and TERM (`run.sh` never
  changes it). After any phone step, check that `settings get global stay_on_while_plugged_in` prints `0`.

## Docs and commits

- `README.md` (English) and `README.zh-TW.md` change together.
- `doc/en` and `doc/zh` change together. `doc/zh` uses Simplified Chinese, as in honk.
- Commit subjects are English and conventional (`feat(android): ...`, `build: ...`). Commits,
  pull requests and docs carry no AI attribution.
