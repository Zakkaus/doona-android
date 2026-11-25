# doona-android

[English](README.md) | 繁體中文

doona-android 是 [honk](https://github.com/daeuniverse/honk) 的 Android 部分，包含兩項：

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
| `doc/zh-TW/troubleshooting.md` | [Android 上的 doona VPN 疑難排解](doc/zh-TW/troubleshooting.md)。 |
| `.github/workflows/android.yml` | CI：腳本檢查、檔案與函式的上限、rustfmt、Clippy、測試、Kotlin 格式、APK 建置、Android lint、JVM 測試和 16 KiB 分頁對齊檢查。 |

`crates/honk-android/Cargo.toml` 從 Git 取得 honk-netstack，固定在 [Glassyiris/honk](https://github.com/Glassyiris/honk) 的 `c5b36a64`，也就是 [daeuniverse/honk#483](https://github.com/daeuniverse/honk/pull/483) 的頂端。

## 建置

先安裝 [`android/scripts/README.md`](android/scripts/README.md#prerequisites) 列出的前置需求：JDK 21、含 API 37.1 平台與 NDK `30.0.16248370` 的 Android SDK、加上 `aarch64-linux-android` target 的 rustup，以及 cargo-ndk 4.1.2。接著建置 debug APK：

```sh
git clone --recursive https://github.com/Zakkaus/doona-android
cd doona-android/android/tunbench
./gradlew assembleDebug
```

APK 位於 `android/tunbench/app/build/outputs/apk/debug/app-debug.apk`。`assembleDebug` 會執行 `:app:buildHonkEngineDebug`，由它提供該建置變體的原生函式庫：在 `crates/honk-android` 執行 `cargo ndk`，參數是 `rustc --lib --crate-type cdylib --release --locked --features bench`。只建 `cdylib`，因為 Cargo 遇到同時是 `rlib` 的編譯單元就不套用 `lto = "fat"`。

[`.github/workflows/android.yml`](.github/workflows/android.yml) 列出 CI 執行的檢查。

## 驗證 Geo 資料版本

在倉庫根目錄使用 `uv` 與 Python 3.11 以上版本，下載鎖定版本的 MetaCubeX 完整 DAT 檔，並將 `geo-manifest.json` 寫入獨立的輸出目錄。Rust 驗證工具沿用 `doona-core` 的工具鏈與 honk commit；在主機上建置還需要 CMake 和 libclang。

```sh
bundle=$(mktemp -d)
uv run crates/doona-core/tools/update_geo.py "$bundle"
uv run crates/doona-core/tools/update_geo.py --check "$bundle"
(cd crates/doona-core/tools/geo-check && DAE_LOCATION_ASSET="$bundle" cargo run --locked -- tests/fixtures/required-labels.dae)
```

[每週排程](.github/workflows/geo-bundle.yml) 驗證最新版本後，將新的 `geo-pins.json` 提交到 `main`，再以 dispatch 觸發 Android 工作流程，因為用 [`GITHUB_TOKEN` 建立的 commit 不會啟動 push 工作流程](https://docs.github.com/en/actions/concepts/security/github_token#when-github_token-triggers-workflow-runs)。若 [`notices.py`](android/doona-android/tools/notices.py) 尚未審查新版資料，排程會失敗，`main` 維持不變。排程必須放在預設分支。DAT 檔不納入版本控制；doona 的 APK 內含鎖定版本的兩個 DAT 檔。

推送 `v*` 標籤時，只要有一個鎖定的 DAT 檔與 MetaCubeX 最新版本不同，CI 就會失敗，也不建置發版 APK。加標籤前可執行 `uv run crates/doona-core/tools/update_geo.py --current` 檢查。需要更新時，先執行 `gh workflow run geo-bundle.yml`，再為包含新版本鎖定檔的 commit 加標籤。

MetaCubeX 會替換 `latest` 的附件，並強制推送 `release` 分支。版本鎖定檔使用含 commit SHA 的快照 URL，大小與 SHA-256 取自 release API。URL 不會指向新版內容，但 GitHub 可能回收舊的 `release` commit；是否設置第一方鏡像仍由維護者決定。

啟動 VPN 時，若核心未使用任何一組 Geo 資料，或 APK 內的發行日期較新，應用程式會解壓縮資料並交給核心。啟動成功後會刪除暫存檔，殘留的暫存檔則在下次啟動時清除。若距上次成功更新已超過設定間隔，VPN 連線時會檢查更新；定期排程也會在已連線時檢查。預設間隔為一週，下載的資料下次啟動 VPN 時才會生效。

## 執行測試

執行測試需要 `adb`，以及開啟 USB 偵錯的手機。也可以拔掉傳輸線、改用無線 adb 執行，量測期間手機就不會被 USB 充電。安裝 APK、用 `run.sh` 執行測試計畫、用 `analyze.py` 把 Perfetto trace 換算成能耗和喚醒次數，都寫在 [`android/scripts/README.md`](android/scripts/README.md)。

## 參與貢獻

回報問題前請閱讀[支援指南](.github/SUPPORT.md)；提交 PR 前請閱讀[貢獻指南](CONTRIBUTING.md)。

## 相關專案

- [honk](https://github.com/daeuniverse/honk)：代理引擎，honk-netstack 在這個倉庫。
- [doona](https://github.com/Zakkaus/doona)：honk 的 web 介面。

## 授權

doona-android 採用 AGPL-3.0-only 授權，見 [LICENSE](LICENSE)。
如果你散布修改版，或執行修改版並讓使用者透過網路與它互動，就必須向這些使用者提供取得對應原始碼的方式。

honk 維持 GPL-3.0-only 授權，依 [GPL-3.0 第 13 條](https://www.gnu.org/licenses/gpl-3.0.html#section13) 與 doona-android 結合。
hev-socks5-tunnel 子模組維持原有的 MIT 授權。
