# doona-android

English | [繁體中文](README.zh-TW.md)

doona-android holds the Android side of [honk](https://github.com/daeuniverse/honk):
`honk-android`, a Rust crate that runs honk-netstack on an Android VpnService tun, and TunBench,
an Android app that benchmarks it against
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) on one phone.

This is test tooling. honk ships nothing from this repository, and the bench figures are not a
CI gate.

## Layout

| Path | Contents |
| --- | --- |
| `crates/honk-android/` | The Rust crate, built as `libhonkengine.so` with cargo-ndk. It is a standalone crate with its own `Cargo.lock`, `rust-toolchain.toml` and release profile. |
| `android/tunbench/` | TunBench, the Gradle app (package `dev.tunbench`). hev-socks5-tunnel is a submodule under `app/src/main/jni/`. |
| `android/scripts/` | `run.sh` and `analyze.py` for bench runs, `build-netstack-bench.sh`, and the build and run guide. |
| `android/plans/` | Bench plans: `smoke.json`, `compare.json` (the first round) and `compare-v2.json` (the second, which adds bulk at a 2000 Mbit/s target rate). |
| `doc/en/design/android-bench.md` | The harness design and the rules that make its figures valid ([Chinese](doc/zh/design/android-bench.md)). |
| `doc/en/troubleshooting.md` | [Troubleshoot the doona VPN on Android](doc/en/troubleshooting.md). |
| `.github/workflows/android.yml` | CI: script checks, file and function limits, rustfmt, Clippy, tests, Kotlin format, the APK build, Android lint, the JVM tests and the 16 KiB page alignment check. |

`crates/honk-android/Cargo.toml` takes honk-netstack from Git, pinned to `c5b36a64` on
[Glassyiris/honk](https://github.com/Glassyiris/honk), the head of
[daeuniverse/honk#483](https://github.com/daeuniverse/honk/pull/483).

## Build

Install the prerequisites listed in
[`android/scripts/README.md`](android/scripts/README.md#prerequisites): JDK 21, the Android SDK
with the API 37.1 platform and NDK `30.0.16248370`, rustup with the `aarch64-linux-android`
target, and cargo-ndk 4.1.2. Then build the debug APK:

```sh
git clone --recursive https://github.com/Zakkaus/doona-android
cd doona-android/android/tunbench
./gradlew assembleDebug
```

The APK is `android/tunbench/app/build/outputs/apk/debug/app-debug.apk`. `assembleDebug` runs
`:app:buildHonkEngineDebug`, which provides that variant's native library: it runs `cargo ndk` in
`crates/honk-android` with `rustc --lib --crate-type cdylib --release --locked --features bench`.
It builds the `cdylib` alone because Cargo skips `lto = "fat"` for a unit that is also an `rlib`.

[`.github/workflows/android.yml`](.github/workflows/android.yml) lists the checks CI runs.

## Verify geo bundle pins

From the repository root, use `uv` with Python 3.11 or newer to fetch the pinned full MetaCubeX DATs
and write `geo-manifest.json` to a separate output directory. The Rust verifier uses the
`doona-core` toolchain and honk revision; its host build also needs CMake and libclang.

```sh
bundle=$(mktemp -d)
uv run crates/doona-core/tools/update_geo.py "$bundle"
uv run crates/doona-core/tools/update_geo.py --check "$bundle"
(cd crates/doona-core/tools/geo-check && DAE_LOCATION_ASSET="$bundle" cargo run --locked -- tests/fixtures/required-labels.dae)
```

[The weekly workflow](.github/workflows/geo-bundle.yml) verifies the newest release, commits the new
`geo-pins.json` to `main` and dispatches the Android workflow, because a commit made with
[`GITHUB_TOKEN` starts no push workflow](https://docs.github.com/en/actions/concepts/security/github_token#when-github_token-triggers-workflow-runs).
A snapshot that [`notices.py`](android/doona-android/tools/notices.py) has not reviewed fails the run
and leaves `main` unchanged. The schedule requires the workflow on the default branch.
The build bundles the pinned DATs into the doona APK. The repository does not track them.

CI fails a `v*` tag when a pinned DAT differs from the newest MetaCubeX release. To check before
tagging, run `uv run crates/doona-core/tools/update_geo.py --current`. To update the pins, run
`gh workflow run geo-bundle.yml`, then tag a commit that has them.

MetaCubeX replaces its `latest` assets and force-pushes its `release` branch. The pins use
commit-qualified snapshot URLs, with sizes and SHA-256 digests from the release API. The URLs
cannot move to newer bytes, but GitHub may garbage-collect old `release` commits. A first-party
mirror is an open owner decision.

On VPN start, the app offers the bundled pair when no geo generation is active or the APK's
release date is newer. It deletes the temporary files after a successful start and clears any
leftovers on the next start. The app checks for updates on connection when the last success is
older than the configured interval. A periodic job also checks while the VPN runs. The default
interval is one week. Downloads take effect at the next VPN start.

## Run a bench

A bench run needs `adb` and a phone with USB debugging. It can also run with the cable unplugged,
over wireless adb, so USB power does not charge the phone during the measurement.
[`android/scripts/README.md`](android/scripts/README.md) covers installing the APK, running a plan
with `run.sh`, and turning the Perfetto trace into energy and wakeup figures with `analyze.py`.

## Contributing

Use the [support guide](.github/SUPPORT.md) for bug reports and [CONTRIBUTING.md](CONTRIBUTING.md) for pull requests.

## Related projects

- [honk](https://github.com/daeuniverse/honk): the proxy engine. honk-netstack lives there.
- [doona](https://github.com/Zakkaus/doona): the web UI for honk.

## License

doona-android is licensed under AGPL-3.0-only; see [LICENSE](LICENSE).
If you distribute a modified version or run one that users interact with over a network, you must offer those users its corresponding source code.

honk remains GPL-3.0-only and is combined with doona-android under [GPL-3.0 section 13](https://www.gnu.org/licenses/gpl-3.0.html#section13).
The hev-socks5-tunnel submodule keeps its own MIT license.
