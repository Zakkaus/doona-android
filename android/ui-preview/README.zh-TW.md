# TunBench 介面預覽

[English](README.md) | 繁體中文

TunBench 控制畫面的 Jetpack Compose 預覽版。畫面上是示範資料，不執行引擎；正式的測試 app 在 [`android/tunbench`](../tunbench)。這個分支不會合併。

畫面照 Google Home 首頁的版面、比例與字型。所有顏色都是動態色彩的角色，Android 12 以上取自桌布，所以換桌布或主題色時整個畫面跟著變色。畫面上只有執行中引擎的控制卡著色。

## 建置

需要裝有 platform 37.1（`android-37.1`）的 Android SDK 與一個 JDK；Gradle 會下載 `gradle/gradle-daemon-jvm.properties` 指定的工具鏈。

```sh
cd android/ui-preview
./gradlew installDebug          # 安裝到連接的手機（Android 10 以上）
./gradlew recordRoborazziDebug  # Roborazzi 截圖，輸出在 app/build/outputs/roborazzi
tools/check-spacing.sh          # theme/ 以外不得出現 dp 字面值
```

## 檔案位置

| 路徑 | 內容 |
|---|---|
| `app/src/main/kotlin/dev/tunbench/preview/theme/HomeTheme.kt` | 所有尺寸、形狀、字樣與備用配色 |
| `app/src/main/kotlin/dev/tunbench/preview/ui/` | 畫面：控制、設定與預留頁 |
| `app/src/test/kotlin/dev/tunbench/preview/` | 截圖測試，包含其他種子色 |

使用 AGP 9.5、Kotlin 2.4、Compose BOM 2026.11.01（Material 3 1.5 Expressive）、Vico 3.3 與 Roborazzi 1.79。

## 授權

Google Sans 採用 SIL Open Font License 1.1（`app/src/main/assets/licenses/google-sans/`）。圖示是 Material Symbols，採用 Apache License 2.0，清單見 `NOTICE`。
