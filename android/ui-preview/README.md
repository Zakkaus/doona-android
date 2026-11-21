# TunBench UI preview

English | [繁體中文](README.zh-TW.md)

A Jetpack Compose preview of the TunBench control screen. It shows sample data and runs no
engine; the bench app itself is in [`android/tunbench`](../tunbench). This branch is not meant to
be merged.

The screen copies the layout, proportions and type of the Google Home home tab. Every colour is a
role of the dynamic colour scheme, taken from the wallpaper on Android 12 and later, so changing
the wallpaper or theme colour recolours the whole screen. Only the running engine's control card is
tinted.

## Build

Requirements: the Android SDK with platform 37.1 (`android-37.1`), and a JDK; Gradle downloads
the toolchain that `gradle/gradle-daemon-jvm.properties` names.

```sh
cd android/ui-preview
./gradlew installDebug          # install on a connected phone (Android 10 or later)
./gradlew recordRoborazziDebug  # Roborazzi screenshots in app/build/outputs/roborazzi
tools/check-spacing.sh          # no dp literal outside theme/
```

## Where things are

| Path | Contents |
|---|---|
| `app/src/main/kotlin/dev/tunbench/preview/theme/HomeTheme.kt` | Every size, shape, type style and the fallback colour scheme |
| `app/src/main/kotlin/dev/tunbench/preview/ui/` | The screens: control, settings, placeholders |
| `app/src/test/kotlin/dev/tunbench/preview/` | Screenshot tests, including other seed colours |

Stack: AGP 9.5, Kotlin 2.4, Compose BOM 2026.11.01 with Material 3 1.5 Expressive, Vico 3.3,
Roborazzi 1.79.

## Licences

Google Sans is under the SIL Open Font License 1.1 (`app/src/main/assets/licenses/google-sans/`).
The icons are Material Symbols under the Apache License 2.0; `NOTICE` lists them.
