# Android benchmark harness

Tools for measuring honk-netstack on arm64 Android phones. CI builds and checks the app and the
runner but never measures; the results are not a performance gate.

## What it measures

1. **The netstack alone.** honk-netstack's [`tcp_bulk`][tcp_bulk] example runs an endpoint-mode
   client stack and a transparent-mode server stack in one thread and passes packets between them
   directly. There is no tun and no socket, so the CPU time is the cost of the two stacks per
   packet and per byte. The `stack` Criterion bench ([`benches/stack.rs`][stack]) is cross-built
   alongside it. Both live in honk, not in this repository; `build-netstack-bench.sh` builds them
   in a honk checkout (see [Build](#build)).
2. **The Android VpnService path.** The TunBench app (`android/tunbench/`, package
   `dev.tunbench`) opens a VpnService tun that routes `198.18.0.0/15` and runs one of two engines
   on it:
   - `hev`: [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 2.20.0, the
     baseline. It relays each flow to a SOCKS5 server.
   - `honk`: `crates/honk-android` (`libhonkengine.so`), honk's Android VpnService
     integration: honk-netstack in transparent mode on the tun fd. It relays every TCP flow to one fixed upstream. It ignores UDP.

3. **Bench mode.** `BenchActivity` runs a JSON plan inside the TunBench process: a generator,
   the engine, a SOCKS5 relay (hev only) and a sink, each on its own pinned CPUs, so no outside
   load generator weighs on the result. It writes one JSON line per measurement. `run.sh` records
   a Perfetto trace (power rails and scheduler events) beside the run, and `analyze.py` turns the
   trace into joules per rail and wakeups per thread for each measurement's window.

## Prerequisites

- A Linux x86_64 build host. On other hosts, set `NDK_HOST_TAG` to the NDK's prebuilt
  directory name.
- JDK 21 (`JAVA_HOME`).
- Android SDK with NDK `30.0.16248370` (`ANDROID_HOME`, or `sdk.dir` in
  `android/tunbench/local.properties`).
- rustup with the repository's toolchain and the `aarch64-linux-android` target:
  `rustup target add aarch64-linux-android`.
- cargo-ndk 4.1.2: `cargo install cargo-ndk --version 4.1.2 --locked`.
- The hev submodule: `git submodule update --init --recursive`.
- For bench mode: `adb` on the host and a phone with USB debugging. The APK must be a debug
  build, because `run.sh` copies the plan in with `run-as`. Perfetto ships with the phone.
- [uv](https://docs.astral.sh/uv/) for `analyze.py`. It uses the system Python (3.11 or newer; set
  `UV_PYTHON_DOWNLOADS=never` to forbid downloads) and installs `perfetto==0.59.1`, pinned to
  the phone's Perfetto, which fetches its `trace_processor` binary on first use.
- `shellcheck` and `shfmt` for the script gates.

## Build

```sh
(cd android/tunbench && ./gradlew assembleDebug)
HONK_DIR=../honk android/scripts/build-netstack-bench.sh   # tcp_bulk, tcp_bulk-native, netstack-criterion -> android/scripts/out/
(cd android/scripts && uv sync)               # the analyzer's environment: perfetto, pytest, ruff
```

The Gradle build takes the NDK from the Android Gradle plugin and needs no environment script.
`build-netstack-bench.sh` sources `android-env.sh` itself, which sets the NDK linker variables for
cargo: set `ANDROID_HOME` (default `$HOME/Android/Sdk`) and, on a non-Linux host, `NDK_HOST_TAG`
before running it.

`build-netstack-bench.sh` builds in the honk checkout named by `HONK_DIR` and fails when
`HONK_DIR` is unset. The checkout must contain the `tcp_bulk` example; the known-good revision is
[Glassyiris/honk](https://github.com/Glassyiris/honk) `5d0a900d` (branch `feat/android-bench`).
Leave `CARGO_TARGET_DIR` unset or set it to `$HONK_DIR/target`, because the script copies the
binaries from there.

The Gradle build runs the `buildHonkEngine` task, which runs `cargo ndk` in `crates/honk-android`
with the Android Gradle plugin's NDK to build `libhonkengine.so`: `cargo ndk ... rustc --lib
--crate-type cdylib --release --locked --features bench`. It overrides the manifest's
`["cdylib", "rlib"]`, because Cargo applies `lto = "fat"` only when every crate type supports it. It builds hev with ndk-build.
The APK is `android/tunbench/app/build/outputs/apk/debug/app-debug.apk`.

`crates/honk-android` is a standalone crate with its own `Cargo.lock`. It takes honk-netstack as a
git dependency pinned to one honk revision. Its relay test runs on the build host:
`cd crates/honk-android && cargo test --release`.

## Run

Netstack alone, on the phone:

```sh
adb push android/scripts/out/tcp_bulk /data/local/tmp/
adb shell /data/local/tmp/tcp_bulk --mtu 8500 --flows 1 --cpu 7
```

`tcp_bulk --help` prints the usage line; the flags are documented at the top of
[`tcp_bulk.rs`][tcp_bulk].

[tcp_bulk]: https://github.com/Glassyiris/honk/blob/5d0a900d95f8e23a88f66b0aad7b72b1c2ed3dda/crates/honk-netstack/examples/tcp_bulk.rs
[stack]: https://github.com/Glassyiris/honk/blob/5d0a900d95f8e23a88f66b0aad7b72b1c2ed3dda/crates/honk-netstack/benches/stack.rs

The VpnService path:

```sh
adb install -r android/tunbench/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.tunbench/.MainActivity     # approve the VPN prompt once
adb shell am start -n dev.tunbench/.MainActivity --es action start \
    --es engine honk --es host 127.0.0.1 --es port 5202 --es mtu 8500 --es cpus 7
adb shell am start -n dev.tunbench/.MainActivity --es action stop
```

Intent extras:

| Extra | Meaning |
| --- | --- |
| `action` | `start` or `stop` |
| `engine` | `honk` or `hev` |
| `host`, `port` | `hev`: the SOCKS5 server. `honk`: the upstream that receives every flow. |
| `mtu` | tun MTU |
| `cpus` | CPU list for the engine's worker thread, e.g. `7` or `4-7` |

Once a second the app writes telemetry to `files/telemetry.txt` as one `key=value` line: the
engine, whether it runs, the CPUs requested, and the worker thread's CPU time, current core,
migrations, context switches, allowed CPUs and cpuset. Read it with
`adb shell run-as dev.tunbench cat files/telemetry.txt`.

### Bench plans

```sh
adb install -r android/tunbench/app/build/outputs/apk/debug/app-debug.apk
android/scripts/run.sh android/plans/smoke.json          # prints the results directory
uv run --project android/scripts android/scripts/analyze.py android/results/<run id>
```

`android/plans/smoke.json` is a short check of every case; `compare.json` is the full matrix
(five interleaved repeats). A plan lists `engines` (`hev`, `honk`), `mtu`, `cases` (`bulk` with
`dir` and `flows`, `idle`, `tail`, `sparse`), `pin` (disjoint CPU lists for `engine`,
`generator`, `sink` and, for hev, `relay`), `repeat`, `cooldown_seconds` and `interleave`. The
runner rejects a plan with an unknown field or an out-of-range value before it starts.

`run.sh PLAN.json [RUN_ID]` pushes the plan, starts Perfetto and then `BenchActivity`, waits for
the app's `done` file within a deadline taken from the plan, and pulls `plan.json`,
`results.jsonl`, `done` and `trace.pftrace` into `android/results/<run id>/` with `meta.txt`
(APK and plan hashes, build id, `sdk_full`, thermal status, `stay_on_while_plugged_in`) and
`screen.txt` (wakefulness,
display state, brightness). The header of the script lists the `am start` contract with the app and
its environment variables. `adb shell` calls reach the phone while it measures, so it checks for
the finish only every 30 s (`POLL`).

The screen stays interactive with the panel black: the app shows a full-screen black Activity
that keeps the screen on while it is in front. `run.sh` wakes the phone before the start and
reads `mWakefulness` first, recording `woke=1` in `meta.txt` if the phone was not Awake, `woke=0`
if it was, and `woke=unknown` with a warning if the read failed or named no known state. With
`woke=1` the exit trap sends `KEYCODE_SLEEP` and logs the wakefulness it reads afterwards; with
`woke=unknown` it leaves the phone as it is. It does not fail the run on that reading, because the
key may take effect late. A few seconds after the start `run.sh` checks
`mWakefulness=Awake` (from `dumpsys power`), the display state (from `dumpsys display`, which
Android 17 no longer prints in `dumpsys power`) and `BenchActivity` as the resumed top activity, and
aborts otherwise. `run.sh` never changes `stayon`: it records `stay_on_while_plugged_in` at the
start and the end, refuses to wake the phone if it is not 0 at the start and fails the run if it is
not 0 at the end, because the phone would then never sleep on USB power. Its exit trap, on success,
failure, Ctrl-C and `kill`, sends the stop intent with this run's id if its start was sent and its finish was
not confirmed (a `done` after the deadline does not count), stops Perfetto and waits for it to exit, saves what the app wrote and pulls
the trace. It deletes the phone's copy of the trace only after a pull. A failed stop fails the
run, and so does any of `plan.json`, a non-empty `results.jsonl`, `done` and the trace not pulled;
the log names the missing files. Every adb call in the trap has a time limit, and Ctrl-C does not
interrupt it. The stop intent cancels only the run in progress with that id and never turns the
screen on; Back on the black screen cancels the run in progress. `kill -9` (SIGKILL) cannot run
the trap: stop the app with
`adb shell am start -n dev.tunbench/.BenchActivity --ez stop true --es run <run id>`, stop Perfetto,
check that `adb shell settings get global stay_on_while_plugged_in` prints 0, and, if `meta.txt`
has `woke=1`, put the phone back to sleep with `adb shell input keyevent KEYCODE_SLEEP`.

### Analyze

`analyze.py RUN_DIR` writes `RUN_DIR/analysis.jsonl`: each runner record with `joules` (per
rail, listed from the trace), `wakeups` (per role and thread, from `thread_state`: runnable after
a sleep, not a preemption or yield) and `invalid`, the reason each null value is null. The window is
`[boottime_start_ns, boottime_end_ns]`, the span of the runner's CPU samples and counted bytes
(the drain after `boottime_cut_ns` included). The rails are every `power_rails` counter track,
grouped by track id; two tracks with one name come out as `name#id`. Boundaries are interpolated between the samples around them. A record the runner rejected
gets only nulls. A missing rail, a boundary outside the samples, a counter reset or a gap above
`--max-gap-ms` (1000) makes that value invalid; lost scheduler data in the trace, or scheduler
data that ends before the window does, makes the wakeups invalid. A sleep that ends straight in
running counts as a wakeup (the waker ran before the switch-out was recorded). The
runner's fields, `scan_start_ns` and `scan_end_ns` among them, pass through unchanged. It prints the
poll interval found. Quick table: `jq -r '[.seq,.engine,.case.kind,.flows,.joules.VSYS_PWR_VBATT]|@tsv'
RUN_DIR/analysis.jsonl`.

Gates for `run.sh` and `analyze.py`: `shellcheck android/scripts/run.sh`, `shfmt -d
android/scripts/run.sh` and, in `android/scripts`, `uv run ruff check`, `uv run ruff format
--check`, `uv run pytest`. CI runs all of these but `shfmt`, with `uv run` locked to `uv.lock`, in a
step before the Gradle step.

## Known limits

- `crates/honk-android` is the first version of honk's Android VpnService integration: one
  worker thread on an epoll loop, TCP only. Its JNI API (`dev.tunbench.HonkEngine`) may change
  as the integration is adapted.
- `cpus` must fall inside the app's current cpuset (`/proc/<pid>/cpuset`). Otherwise the
  affinity request fails or is narrowed. The telemetry line shows the CPUs in effect.
- Turning the screen off cuts throughput to about a third, which is why bench mode keeps the
  phone interactive behind a black Activity; a lit screen would add display power to the rails.
- Use MTU 8500, the app's default.
- The pins in `android/plans/` (engine 7, relay 6, generator 0-1, sink 2-3) are for a Pixel 10 Pro.
