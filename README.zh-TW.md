# honk-android

[English](README.md) | 繁體中文

honk-android 是 [honk](https://github.com/daeuniverse/honk) 的 Android 部分，包含兩項：

- `honk-android`：在 Android VpnService 的 tun 上執行 honk-netstack 的 Rust crate；
- TunBench：在同一部手機上比較它和 [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) 的 Android app。

這是測試工具。honk 不發佈這個倉庫的任何內容，測得的數據也不是 CI 門禁。

## 目錄

| 路徑 | 內容 |
| --- | --- |
| `crates/honk-android/` | Rust crate，用 cargo-ndk 建置成 `libhonkengine.so`。它是獨立的 crate，有自己的 `Cargo.lock`、`rust-toolchain.toml` 和 release profile。 |
| `android/tunbench/` | TunBench 的 Gradle 專案（套件名 `dev.tunbench`）。hev-socks5-tunnel 是 `app/src/main/jni/` 下的 submodule。 |
| `android/scripts/` | 執行測試的 `run.sh`、分析用的 `analyze.py`、`build-netstack-bench.sh`，以及建置與執行說明。 |
| `android/plans/` | 測試計畫：`smoke.json`、`compare.json`（第一輪）和 `compare-v2.json`（第二輪，加上目標速率 2000 Mbit/s 的 bulk）。 |
| `doc/en/design/android-bench.md` | 測試工具的設計，以及保證數據有效的規則（[簡體中文版](doc/zh/design/android-bench.md)）。 |
| `.github/workflows/android.yml` | CI：腳本檢查、rustfmt、Clippy、測試、Kotlin 格式、APK 建置、Android lint 和 16 KiB 分頁對齊檢查。 |

`crates/honk-android/Cargo.toml` 從 Git 取得 honk-netstack，固定在 [Glassyiris/honk](https://github.com/Glassyiris/honk) 的 `c5b36a64`，也就是 [daeuniverse/honk#483](https://github.com/daeuniverse/honk/pull/483) 的頂端。

## 建置

先安裝 [`android/scripts/README.md`](android/scripts/README.md#prerequisites) 列出的前置需求：JDK 21、含 API 37.1 平台與 NDK `30.0.16248370` 的 Android SDK、加上 `aarch64-linux-android` target 的 rustup，以及 cargo-ndk 4.1.2。接著建置 debug APK：

```sh
git clone --recursive https://github.com/Zakkaus/honk-android
cd honk-android/android/tunbench
./gradlew assembleDebug
```

APK 位於 `android/tunbench/app/build/outputs/apk/debug/app-debug.apk`。建置 app 之前，Gradle 的 `buildHonkEngine` 任務會在 `crates/honk-android` 執行 `cargo ndk`，參數是 `rustc --lib --crate-type cdylib --release --locked --features bench`。只建 `cdylib`，因為 Cargo 遇到同時是 `rlib` 的編譯單元就不套用 `lto = "fat"`。

[`.github/workflows/android.yml`](.github/workflows/android.yml) 列出 CI 執行的檢查。

## 執行測試

執行測試需要 `adb`，以及開啟 USB 偵錯的手機。也可以拔掉傳輸線、改用無線 adb 執行，量測期間手機就不會被 USB 充電。安裝 APK、用 `run.sh` 執行測試計畫、用 `analyze.py` 把 Perfetto trace 換算成能耗和喚醒次數，都寫在 [`android/scripts/README.md`](android/scripts/README.md)。

## 相關專案

- [honk](https://github.com/daeuniverse/honk)：代理引擎，honk-netstack 在這個倉庫。
- [doona](https://github.com/Zakkaus/doona)：honk 的 web 介面。

## 授權

GPL-3.0-only，見 [LICENSE](LICENSE)。hev-socks5-tunnel submodule 沿用它自己的 MIT 授權。
