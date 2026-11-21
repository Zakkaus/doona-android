# doona-Android sketch

Native Android sketch of doona, the console for the honk backend, in doona's standard-edition look: the 活動 and
策略 screens and a group page, in Jetpack Compose. Every colour, size, shape and type value lives in
`app/src/main/kotlin/dev/doona/android/theme/DoonaTheme.kt`, taken from doona's design tokens.

```sh
. ~/.cache/android-dev/env.sh
./gradlew recordRoborazziDebug -Ddoona.shots=<dir>   # 412 × 915 dp, zh-TW: light, dark, font scale 1.15
tools/check-tokens.sh                                 # no dp, sp or colour literal outside the theme
```

`tools/icons.py` and `tools/fonts.py` rebuild the drawables and fonts from a doona checkout (fontTools and brotli);
`NOTICE` lists their licences.
