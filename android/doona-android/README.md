# doona-Android

Native Android client for the honk backend, in doona's standard-edition look, in Jetpack Compose. The design system
has three layers (`designsystem/`): `tokens` holds the raw values taken from doona's design tokens (palette, type, spacing
and radius scales, sizes); `theme` names them as roles (`DoonaTheme.colors.surface`, `.colors.text.secondary`,
`.spacing.section`, `.type.body`); `components` holds the `Doona*` components, each with its defaults next to it. Screens
and components read roles only.

```sh
. ~/.cache/android-dev/env.sh
./gradlew spotlessCheck :app:lintDebug :app:testDebugUnitTest verifyRoborazziDebug
./gradlew recordRoborazziDebug                        # rewrite the goldens in app/src/test/screenshots after a change you meant
./gradlew recordRoborazziDebug -Ddoona.shots=<dir>    # the same renders in <dir>, to look at without touching the goldens
tools/check-tokens.sh                                 # no dp, sp, colour or alpha literal outside designsystem/tokens (bar a component's own Defaults object)
tools/check-layers.sh                                 # material3 and tokens stay in designsystem/, feature/ builds no interactive element
```

Goldens (`app/src/test/screenshots`, fake data only) cover every screen at 412 × 915 dp in zh-TW in light, dark and at
font scale 1.15 and 2.0, plus a 840 dp wide window and a 915 × 412 dp landscape one, and the three component gallery
pages (`designsystem/components/ComponentPreviews.kt`) in the four phone variants. Each render also goes through
Google's accessibility checks. Spacing, contrast and reachability are asserted by `SpacingCheckTest` (at every scroll
offset), `ContrastTest` and `ReachabilityTest`; `SpacingRulesTest`, `ProbeDetectionTest`, `BlankContentTest` and
`AccessibilityCheckTest` plant a defect in each rule and keep the case, so a check that stops seeing its defect fails.

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
  literal sizes in `feature/`. Gaps are spacing roles (`DoonaGap(DoonaTheme.spacing.section)`), never numbers. A component that is always interactive takes a required callback; one that can be static (`DoonaCard`, `DoonaTagChip`, `DoonaPageTabs`) takes a nullable one; an action with no screen behind it yet is passed `NOT_WIRED`, so it can be found. Strings go in `values/strings.xml` and `values-zh-rTW/strings.xml`.
- **Previews:** in `<Screen>Previews.kt`, light and dark, built from `FakeEngine` fixtures through the same pure
  state function; the screenshot test reuses that fixture. A new component goes on a page of `ComponentPreviews.kt`
  in every state it has.
- **Tests:** a JVM test of the ViewModel on `FakeEngine` (wrap it with `Engine by FakeEngine()` to make one action
  fail), and a case in `screenCases()` (`testing/ScreenCases.kt`) that names the layout roles the screen shows and the spacing role of each gap between the blocks of its sheet: it gets the spacing check on a phone, a wide window and
  a landscape one, the reachability check, and a golden in every variant. Record the goldens and look at them before
  committing.
