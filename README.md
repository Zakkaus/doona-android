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

## Run a bench

A bench run needs `adb` and a phone with USB debugging. It can also run with the cable unplugged,
over wireless adb, so USB power does not charge the phone during the measurement.
[`android/scripts/README.md`](android/scripts/README.md) covers installing the APK, running a plan
with `run.sh`, and turning the Perfetto trace into energy and wakeup figures with `analyze.py`.

## Related projects

- [honk](https://github.com/daeuniverse/honk): the proxy engine. honk-netstack lives there.
- [doona](https://github.com/Zakkaus/doona): the web UI for honk.

## License

GPL-3.0-only; see [LICENSE](LICENSE). The hev-socks5-tunnel submodule keeps its own MIT license.
