# doona-Android

Native Android client for the honk backend, in doona's standard-edition look, in Jetpack Compose. The design system
has three layers (`designsystem/`): `tokens` holds the raw values taken from doona's design tokens (palette, type, spacing
and radius scales, sizes); `theme` names them as roles (`DoonaTheme.colors.surface`, `.colors.text.secondary`,
`.spacing.section`, `.type.body`); `components` holds the `Doona*` components, each with its defaults next to it. Screens
and components read roles only.

Auto and the region groups use honk's Score policy. Proxy and the rule groups remain Selectors.
Pinning a Score member overrides automatic selection until you clear the pin. A TCP setup failure
can try one other eligible member before the app sends any data; the retry shares the original deadline.
A network outage does not permanently exclude failed nodes or start background probes. A subscription
refresh keeps probe latency for unchanged nodes.

`Engine.renameSubscription` changes only the display name. Names may repeat because subscriptions,
node memberships and selections use IDs. Add and rename accept 1 to 64 Unicode code points after
trimming and reject controls and line separators. `Engine.nodeShareLink` returns a `ShareLink` only
for a `share_links` feed; read its `uri` explicitly when sharing. Other formats, local nodes and
unknown IDs return `NotFound`. Refresh a legacy subscription with no recorded format before sharing.

`Engine.setDnsUpstreams` persists one to four distinct unicast IPv4 or IPv6 endpoints in preference order. The default
is `1.1.1.1:53`, then `8.8.8.8:53`. A bare IP uses port 53; use `IP:port` or `[IPv6]:port` to
override it. `effectiveConfig().dnsUpstreams` reports the active endpoints. New UDP DNS queries and
new TCP DNS connections use the list immediately; existing TCP DNS connections keep theirs.
This setting does not change Android's bootstrap resolvers for node hostnames.

```sh
. ~/.cache/android-dev/env.sh
./gradlew spotlessCheck :app:lintDebug :app:testDebugUnitTest verifyRoborazziDebug
./gradlew recordRoborazziDebug                        # rewrite the goldens in app/src/test/screenshots after a change you meant
./gradlew recordRoborazziDebug -Ddoona.shots=<dir>    # the same renders in <dir>, to look at without touching the goldens
tools/check-tokens.sh                                 # no dp, sp, colour or alpha literal outside designsystem/tokens (bar a component's own Defaults object)
tools/check-components.sh                             # an RV page composes the library: no raw dp, colour, text style, Text/Icon or clickable (tools/check-components.allow lists the existing exceptions)
tools/check-layers.sh                                 # material3 and tokens stay in designsystem/; feature/ and the RV pages build no interactive element
tools/check-samples.sh                                # src/main names no sample, `*Sample` binding or RvData that only src/debug declares
```

To reproduce the pull request APK, run `./gradlew assembleRelease -Pdoona.coreProfile=ci-release`.
This builds the Rust core with thin LTO and 16 codegen units. For the shipping APK, run `./gradlew assembleRelease`
without the property to use fat LTO and one codegen unit.

Goldens (`app/src/test/screenshots`, fake data only) cover every screen at 412 × 915 dp in zh-TW in light, dark and at
font scale 1.15 and 2.0, plus a 840 dp wide window and a 915 × 412 dp landscape one, and the seven component gallery
pages (`designsystem/components/*Gallery.kt`, `ComponentPreviews.kt` has the previews) in the four phone variants. Each render also goes through
Google's accessibility checks. Spacing, contrast and reachability are asserted by `SpacingCheckTest` (at every scroll
offset), `ContrastTest` and `ReachabilityTest`; `SpacingRulesTest`, `ProbeDetectionTest`, `BlankContentTest` and
`AccessibilityCheckTest` plant a defect in each rule and keep the case, so a check that stops seeing its defect fails.

`tools/duck.py` and `tools/fonts.py` rebuild the duck mark and the fonts from a doona checkout (fontTools and brotli for
the fonts); `NOTICE` lists their licences. The font holds only the characters the string resources and Kotlin sources use, so
rebuild it when either gains a character: `uv run --offline --no-project --with fonttools --with brotli python -I
tools/fonts.py <doona checkout> app/src/main`. A character the font lacks falls back to a system face, in another weight
and with a taller line, so a rebuild that takes in such a character moves the rows that show it. To add only the
characters a change needs, pass `--add <files>` after `app/src/main`: the files keep what they hold and gain those
characters. A rebuild keeps the bounding box of the files it replaces; `FontMetricsTest` pins the metrics, and
`tools/font_metrics.py <old dir> <new dir>` compares two builds.

## Diagnostic capture

`AppContainer.logLevels` owns the core log level for the process. A new process starts at Info.
Selecting Debug starts a one-hour capture measured in elapsed time, including deep sleep.
`remainingMs()` gives the countdown. The capture ends when the tunnel stops or fails, or
when the app writes a report. If the core refuses the reset, the app retries once. The app
never saves the level.

The app and VPN service share a ring of 200 recent events. One background writer saves each
event's time, level and scrubbed text in the app's no-backup directory. Reports load the saved
events after a restart. Visited domains and app package names stay in memory; a process that dies
before a write finishes can lose the newest events. A JVM crash file preserves up to 50
allowlisted actions from its own process within the 64 KiB file limit.

`AppEventRingTest` uses a fresh event log per test and a plain Android `Application`. App startup
and background workers cannot write into the ring under test.

The ZIP records each group's TCP and UDP choices and pins using scrubbed node and group identifiers.

The ZIP omits node endpoints and local IP addresses by default. With either option enabled, it
reads endpoints only for listed nodes or IPs from Android's active link. For structured event
records, the ZIP and GitHub draft omit visited domains by default and include app package names
by default. Neither output extracts these fields from log text or collects new traffic data.
Subscription links, tokens, credentials and raw payloads are not export options.

A report can also include a GitHub link that fills in the bug form with failure context, device details,
and at most 50 recent actions. The ZIP and link use the same scrubber and event snapshot. The link drops
the oldest actions first to keep the encoded URL below 8,000 characters. No network request is made;
the reporter completes the remaining fields and attaches the ZIP on GitHub.

## Icons and flags

`:icons` holds the complete Material Symbols Rounded set (Apache-2.0): filled, weight 500, 24 px, one vector drawable
`sym_<name>` each, and the weight-600 variants `sym_add_w600` and `sym_close_w600`. `tools/gen_icons.py` regenerates the
module from a pinned commit of google/material-design-icons; `--work` is a new, empty directory outside the
repository. The app names an icon only by a static `dev.doona.android.icons.R.drawable.sym_*`
field, so the release build's resource shrinker keeps the ones the code uses and strips the rest;
`tools/check-layers.sh` fails on `getIdentifier(` anywhere, which would keep them all. `NOTICE` and the modules'
`NOTICE` files list the licences.

`:flags` holds the complete circle-flags set (MIT): every flag of upstream's `flags/*.svg` as a vector drawable
`flag_<code>` (a `-` in the code written `_`), and `flagResource(code)`, the generated `when` from a code (any case, with
upstream's links such as `uk` and `un`) to its drawable, or null. A flag is chosen by code at run time, so a release
build keeps all of them once the table is reachable. `tools/gen_flags.py` regenerates it from a pinned commit of
HatScripts/circle-flags with its own SVG converter, which accepts only the shapes upstream uses; language flags and the
non-geographic ones in `flags/other` are not included. `:flags:testDebugUnitTest` checks that the table reaches every
drawable.

## Components: which one to use

All in `designsystem/components`; a screen builds none of these itself (`tools/check-layers.sh`). A component that is
always interactive takes a required callback; one that can be static takes a nullable one.

| You need | Use | Notes |
| --- | --- | --- |
| A page with a top bar and a sheet | `DoonaPage`, `DoonaPageTitle`, `DoonaPageTabs` | `DoonaHubShell` puts the hub bar, or a rail on a short window, around it. The column is 600 dp at most, centred, and at the rail's edge on a short window |
| A page whose content is a long list, such as the apps of a device | `DoonaTopBar` over `DoonaLazyPageSheet { items(...) }` in a column, as `DoonaPage` builds its own | The same chrome as `DoonaPageSheet`, with a `LazyListScope` for content, so only the rows in view are composed. A `LazyColumn` inside `DoonaPageSheet` throws: its scroll gives the list no height limit |
| Group things | `DoonaCard`; `DoonaControlCard` for an icon, a label and a control; `DoonaMetricTile` for a figure | Gaps are `DoonaTheme.spacing` roles: `DoonaGap(...)` between blocks |
| The one action of a page or dialog | `DoonaButton` (primary) | A pill. Never two primary buttons in one place |
| An alternative next to it | `DoonaButton(style = Secondary)` | Outlined |
| The one action that removes something | `DoonaButton(style = Destructive)` | A primary pill in the destructive fill; the confirmation of a removal, not a general warning colour |
| A minor action, or a dismiss | `DoonaButton(style = Text)` | No fill. In a dialog it is the dismiss |
| An action in progress | `DoonaButton(busy = true)` | Swaps the icon for a spinner and ignores taps; keep the label. `enabled = false` is for an action that cannot run yet |
| An action that is only an icon | `DoonaIconAction` (or `DoonaBarAction` in the top bar) | 48 dp target, needs a label for the reader |
| Several buttons | A `block` apart when stacked, an `item` apart side by side | Each is 36 dp high and hit over 48, so a smaller gap makes the hit areas overlap, which `SpacingRules.touch` fails |
| One line of typed text | `DoonaTextField(state, label, …)` | `TextFieldState` from `rememberTextFieldState()`, or held by the view model. Show an error with `error = …` (words, not only the colour); `supportingText` says what the field wants and gives way to an error; `trailing` takes an icon action at the end of the box; pass `keyboardOptions` and `onImeAction` for the keyboard's type and action |
| Filtering a list by typing | `DoonaSearchField(state, placeholder)` | Search icon and a clear action; the keyboard's action is Search. The placeholder is the field's label for a screen reader, also after typing |
| Pick one of two to five short options | `DoonaSegmented` | Tabular figures. More options, or long labels: `DoonaPicker` |
| Pick from a list of values | `DoonaPicker`; `DoonaFilterPicker` in a filter bar | |
| An on/off setting | `DoonaSwitchRow` | The whole row toggles |
| Choose many from a long list (apps) | `DoonaCheckRow` | Leading slot of `DoonaTheme.size.appIcon`, title, optional subtitle; put the rows in the `items` of a `DoonaLazyPageSheet`. A single setting is a switch, not a check |
| An installed app's launcher icon | `DoonaAppIcon(packageName)` | The leading slot of an app's `DoonaCheckRow`. Read off the main thread and cached; a neutral box of the same size until it has loaded and for a package that is gone |
| Work with no known end | `DoonaProgress` | `Small` alone, `Inline` on a line of text |
| How much of a quota is used | `DoonaMeter(fraction, valueText)` | The value text goes above the bar and wraps; do not draw a bar beside it yourself |
| A list or page with nothing in it | `DoonaEmptyState(icon, title, line, actionLabel, onAction)` | One line that says what to do; the action is the next step, shown when both are given |
| An error that the user should see | `DoonaErrorBanner`; a field's own error for one input | The banner dismisses on tap |
| Ask before a removal or a choice | `DoonaConfirmDialog` | `kind = Destructive` for removing. A tap on the underlay around the card dismisses; the card scrolls in a window too short for it, and button labels wrap whole. The caller stops showing it on confirm or dismiss |
| Status as a colour | `DoonaStatusLight`, `DoonaLatency`, `DoonaBadge` | Say it in words as well |
| A series over time | `DoonaSparkline`; series colours are `DoonaTheme.colors.series1` and `series2`, which no status shares | |

Every component has its states on a gallery page (`designsystem/components/*Gallery.kt`): look there before you add a
variant.

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
- **Manual static nodes:** build drafts with `StaticNodeDraft.Manual.create`. Ports outside `1..65535` fail with
  `StaticNodeDraftException` and reason `Port`. Use the reason to mark the field without calling the engine.
  In a batch, a bad port fails only that row. Submit the remaining drafts and map the engine results back to the
  original UI rows in submission order.
- **UI:** components come from `designsystem/components` only; no `androidx.compose.material3`, `clickable` or
  literal sizes in `feature/`. Gaps are spacing roles (`DoonaGap(DoonaTheme.spacing.section)`), never numbers. A component that is always interactive takes a required callback; one that can be static (`DoonaCard`, `DoonaTagChip`, `DoonaPageTabs`) takes a nullable one; an action with no screen behind it yet is passed `NOT_WIRED`, so it can be found. Strings go in `values/strings.xml` and `values-zh-rTW/strings.xml`.
- **Previews:** in `<Screen>Previews.kt`, light and dark, built from `FakeEngine` fixtures through the same pure
  state function; the screenshot test reuses that fixture. A new component goes on a page of `ComponentPreviews.kt`
  in every state it has.
- **Tests:** a JVM test of the ViewModel on `FakeEngine` (wrap it with `Engine by FakeEngine()` to make one action
  fail), and a case in `screenCases()` (`testing/ScreenCases.kt`) that names the layout roles the screen shows and the spacing role of each gap between the blocks of its sheet: it gets the spacing check on a phone, a wide window and
  a landscape one, the reachability check, and a golden in every variant. Record the goldens and look at them before
  committing.

## Release notices

From this directory, build with `./gradlew assembleRelease`. Run `python3 tools/notices.py` to check
the checked-in inventory and the notices inside the APK. The generator uses Python's standard library,
the project's Gradle wrapper and the pinned Rust toolchain with `rust-docs` installed. After a dependency
or licence change, run `python3 tools/notices.py --write`, rebuild the APK and run the check again.
After a change to `geo-pins.json` alone, `python3 tools/notices.py --geo-pins` rewrites the Geo entries
without an APK; the weekly [Geo workflow](../../.github/workflows/geo-bundle.yml) runs it.
The Maven list covers runtime archives before shrinking, not build plugins, test artifacts or the Compose BOM.
Gradle resolves parent POMs when a library inherits its licences. Each Geo DAT appears in the inventory
only if the APK contains it; bundled bytes must match the pin.

`assets/licenses/index.json` (schema `2`) has two fields besides `schema`:

- `components`: each entry gives `kind`, `name`, `version`, `source` and the SPDX expression in
  `license`. Group entries by the identifiers in `bodies`, but display the expression so readers can
  distinguish `OR`, `AND` and `WITH`. `LicenseRef-` terms have no SPDX body; see `notice` and `extras`.
  Display the component's `attribution` lines and `notice` alongside its licence. Each `extras` entry
  gives the upstream `file`, its `text` key and a `comment` explaining why the full text is retained.
- `ranges`: maps each body identifier or extra text key to `[byteOffset, byteLength]` in
  `licenses/notices.txt`. Read that byte range before decoding UTF-8. Components can share a range,
  including when two archives contain identical NOTICE files.

The `project` component uses the `doona-core` manifest and repository `LICENSE`. The standard bodies in
`tools/spdx/` come from the [SPDX licence list, v3.27.0](https://github.com/spdx/license-list-data/tree/v3.27.0/text).
The generator compares the complete upstream file, ignoring layout, recognised licence titles and
attribution fields. If it cannot match the body, it keeps the original file, including extra terms.
It always retains component NOTICE files, even when their contents match a standard licence.

When adding artwork, classify the release resources in `tools/notices.py`; record each logo's upstream
revision, copyright status and trademark restrictions. Do not list artwork from an unmerged branch.
`tools/notice-sources.json` records upstream paths for licence files missing from published packages and
for the reviewed Geo inputs. Distribution conditions appear in [Third-party notices](../../THIRD_PARTY_NOTICES.md).
