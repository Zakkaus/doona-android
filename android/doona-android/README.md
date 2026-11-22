# doona-Android

Native Android client for the honk backend, in doona's standard-edition look, in Jetpack Compose. The design system
has three layers (`designsystem/`): `tokens` holds the raw values taken from doona's design tokens (palette, type, spacing
and radius scales, sizes); `theme` names them as roles (`DoonaTheme.colors.surface`, `.colors.text.secondary`,
`.spacing.section`, `.type.body`); `components` holds the `Doona*` components, each with its defaults next to it. Screens
and components read roles only.

```sh
. ~/.cache/android-dev/env.sh
./gradlew spotlessCheck :app:lintDebug :app:testDebugUnitTest
./gradlew recordRoborazziDebug -Ddoona.shots=<dir>   # 412 × 915 dp, zh-TW: light, dark, font scale 1.15
tools/check-tokens.sh                                 # no dp, sp or colour literal outside designsystem/tokens
tools/check-layers.sh                                 # material3 and tokens stay in designsystem/, feature/ builds no interactive element
```

`tools/icons.py` and `tools/fonts.py` rebuild the drawables and fonts from a doona checkout (fontTools and brotli);
`NOTICE` lists their licences.

## Writing a screen

`feature/activity/` is the example to copy.

- **Files**, in `feature/<screen>/`: `<Screen>UiState.kt` (state, actions, and a pure function from engine values to
  state), `<Screen>ViewModel.kt`, `<Screen>Screen.kt` (the route and the screen), `<Screen>Previews.kt`.
- **State:** one `@Immutable data class <Screen>UiState` in engine terms; the screen formats and localizes. The
  ViewModel `combine`s the engine's flows into it with `stateIn(viewModelScope, WhileSubscribed(5_000), …)`, seeded
  from the flows' current values. It never touches `Context`, a service or an activity.
- **Wiring:** `<Screen>Route(engine, callbacks)` gets the ViewModel with `viewModel(factory = <Screen>ViewModel.factory(engine))`,
  collects with `collectAsStateWithLifecycle()`, and calls the stateless `<Screen>Screen(state, onAction, …)`. The shell
  (`app/DoonaApp.kt`) adds a `@Serializable` key to `app/Navigation.kt`, carrying ids only, and an `entry<Key>`.
- **Actions:** a `sealed interface <Screen>Action` holds only what the ViewModel handles (`viewModel::onAction`).
  Navigation and steps that need the activity (`onOpenGroup`, `onConnect`) are separate screen parameters.
- **Errors:** engine actions return `Result`. On failure, store `it.errorCode()` in the state, show it with
  `DoonaErrorBanner(stringResource(code.messageRes()), …)` (`feature/ErrorText.kt`), and clear it on dismiss.
- **UI:** components come from `designsystem/components` only; no `androidx.compose.material3`, `clickable` or
  literal sizes in `feature/`. Gaps are spacing roles (`DoonaGap(DoonaTheme.spacing.section)`), never numbers. Strings go in `values/strings.xml` and `values-zh-rTW/strings.xml`.
- **Previews:** in `<Screen>Previews.kt`, light and dark, built from `FakeEngine` fixtures through the same pure
  state function; the screenshot test reuses that fixture.
- **Tests:** a JVM test of the ViewModel on `FakeEngine` (wrap it with `Engine by FakeEngine()` to make one action
  fail), and a Roborazzi shot in `SketchScreenshotTest`.
