**This file wins over briefs, STATE files and code comments.** Where they disagree with it, they are wrong: change them, or ask the owner to change a rule here, which is then written with its date.

# Layout rules (doona-android RV pages)

Owner, 2026-11-25: 「規則要立住，不能每次說劃線就改一次。怎麼處理小字、多行單行、全部元件間距統一，都是要做好、看好的。」 Row alignment had been changed four times, each round by its own reading. This file fixes the values once and the gate (`RvRowRules`, `tools/check-scale.sh`, `tools/check-tokens.sh`) enforces them. A later brief cites a rule's id instead of restating its value.

All values are dp at font scale 1.0 on a 412 dp screen, in a card on the 16 dp gutter (card x 16..396, row content x 32..380). The gate's tolerance is 1 dp. At font scale above 1 only text boxes grow; paddings, discs and minimum heights do not.

## Sources

- **Revolut**: `plans/revolut-android-measured.md` (cited §N; uiautomator dumps and screenshots named in its evidence column) and the row measurements of 2026-11-25 in `plans/briefs/rowalign-1125.md` ("What Revolut does", from `07-set-interface`, `07-privacy`, `07-set-accessibility-b`, `07-set-appearance`). §N converts at 2.55 px/dp.
- **Owner**: decisions on the review page, with the date and the review-page id: iconstyle (`5caf884d`, 2026-11-25), latinrow (`cdbc415f`, 2026-11-25), cjkbreak (`65d6e703`, 2026-11-25), rowfirstline rejected (2026-11-25), and the rules' own request (this file).
- **Owner, s1125c** (2026-11-25 18:55, `plans/DECISIONS.md` 版面與元件): q-s1125c-titlegap (R12), q-s1125c-footgap (footnote), q-s1125c-switchgap (R17), q-s1125c-vname (Exceptions).
- **Decided here** (2026-11-25, the owner's order to settle the rules without a further stop): D1–D4 below. Each follows Revolut's rule; the owner reviews them with the package.

## Table 0: every component, in dp

Icon alignment: **C** = centred on the row; **T** = top on the title's top (the first text line's top). Trailing: **C** centred on the row; **F** centred on the title's first line (its box 26 dp, or the title's own first line if taller). Heights are the row's: one line / with subtitle / wrapped title. "Pad" is the text block's distance to the row's top and bottom.

| Component | Icon alignment | Icon size, x | Text x | Title→subtitle | Trailing x, alignment | Row height 1-line / sub / wrapped | Pad above and below | Gap to next row / next card |
|---|---|---|---|---|---|---|---|---|
| List row, `RvRow` | C one line and wrapped; T with a subtitle | 40 disc, flag or app icon, x 32 | 88 (32 without icon) | 2 (q-s1125c-titlegap) | right edge 380; value, chip, chevron: C; switch, radio, check: F | 58 / 82 / 32 + text (≥ 58) | 16 min, equal above and below | 0 / 16 |
| Settings row, `RvSettingsRow` family | C one line and wrapped; T with a subtitle | 40 disc, x 32 | 88 (32 without icon) | 2 (q-s1125c-titlegap) | right edge 380; value, chip, chevron, switch, radio, check: F with a subtitle, C without; in-card button 40: T with a subtitle, C without | 58 / 82 / 32 + text (≥ 58) | 16 min, equal above and below | 0 / 16 |
| Notice, `RvBanner` | T | 40 disc (24 glyph), x 32 | 88 | 2 (q-s1125c-titlegap) | none; the button stands under the text, 16 below, at x 88 | text + 32 | 16 / 16 | 16 below the card |
| Menu item, `RvLongPressMenu` | C | 24 bare glyph, x 16 in the item | 56 | – | none | 50 | centred | 0 / – |
| Section header | – | – | 16 (page gutter) | – | – | 24 box | 24 above, 12 below to the card | – |
| Footnote | – | – | `footnoteStart` in from the card's start, to `footnoteEnd` in from its end: today 32 to 396 (owner, q-s1125c-footgap; values pending Revolut's measure, R20) | – | – | text | 12 above (to the card; was 8), 24 below | – |
| Card | – | – | – | – | – | – | 16 gutter, 20 radius | 16 |
| Primary button | – | – | – | – | – | 52 | – | 12 to a secondary below |
| Secondary button, chip, segmented control | – | – | – | – | – | 40 | 12 each side in a chip | 8 between buttons |
| Field | – | – | 16 | – | – | 52 | – | 16 |
| Bottom sheet | – | – | 16 | – | – | – | edge 8, bottom 10, handle 8 below the top, title 20 below the top | 8 to the body, 24 to the actions |
| Top bar | – | control 44 | 76 (title after the back control) | – | actions 44, 16 from the edge, 8 apart | 44 | 6 under the status bar | – |

## The spacing scale

One scale, from Revolut's measured steps (§2, §4, §6, §7, §13.4):

| Step | dp | Revolut source |
|---|---|---|
| S0 | 0 | rows edge to edge, no dividers (§7) |
| S1 | 4 | pager peek, label inset (§2, §6) |
| S2 | 8 | disc to label, gap between half tiles, button gap (§6, §2, §4) |
| S3 | 12 | section header to its card, trailing gap (§13.4 with the 24 above) |
| S4 | 16 | gutter, card padding, gap between cards, icon to text, row padding (§2, §7) |
| S5 | 24 | section above, header gap (§13.4) |

Component code uses only these through tokens (`RvSpace`, `RvSettingsSpace`, a component's own `Rv<Component>Size`). `tools/check-tokens.sh` fails on a dp literal outside the token files; `tools/check-scale.sh` fails on a spacing token whose value is not a scale step and not in its exception list (each exception names its source). Sizes of parts (a 40 dp disc, a 52 dp button, a 58 dp row) are not spacing and are in Table 0.

### Spacing tokens on the scale

| Spacing | Token | Step |
|---|---|---|
| Page gutter, side margin | `RvSpace.gutter` | S4 |
| Card padding, row start and end | `RvSpace.rowPadding` | S4 |
| Gap between cards | `RvSpace.cardGap` | S4 |
| Section header above / below | `sectionAbove` / `sectionBelow` | S5 / S3 |
| Footnote above | `footnoteAbove` | S3 (owner, q-s1125c-footgap: 12; was S2, 8) |
| Footnote start and end, in from the card's edges | `footnoteStart`, `footnoteEnd` | S4 and S0 today (R20; values pending) |
| Row text above and below | `rowTextInset` | S4 |
| Icon to text | `rowGap` | S4 |
| Text to trailing part | `trailingGap` | S3 |
| Title to subtitle | `titleToSubtitle` | 2 dp, an exception (below); was S0, the line boxes touching |
| Disc to label (round action) | `discToLabel` | S2 |
| Between two stacked controls | `controlStack` | S3 |
| Notice text to its button | `noticeToAction` | S4 |
| Chart and body to actions | `chartGap`, `controlGap` | S5 |
| Tile gap | `tileGap` | S4 |
| Search to first content, tab block gap, actions to card | `searchGap`, `tabBlockGap`, `actionsToCard` | S4 |
| Tight gaps | `tight`, `captionGap`, `headerInset` | S1 |

Exceptions (token, value, source), also listed in `tools/check-scale.sh`:

- `subGap` 6: a tile's value to its caption, no Revolut step; kept as it is on approved pages.
- `titleToSubtitle` 2: a row's title to its subtitle. Owner, q-s1125c-titlegap (2026-11-25 18:55, chosen from samples at 0, 2 and 4 dp): 「帶小字的列，標題和小字之間 2 dp（原本 0 dp），全 App 一起改」. Revolut's line boxes touch (§7); ours are smaller (latinrow), so the owner asked for the 2 dp.
- `emptyLine` 10, `barGap` 14, `controlGap` of `RvChrome` 10: Revolut's 10 dp between top-bar controls (§4) and the measured chart gaps (§10); not row spacing.
- `headerTop` 152, `heroToActions` 64, `titleToActions` 40 (settings hero, measured against Revolut §5), `actionColumn` 96: page positions and sizes of the hero, not gaps.
- `accentInset` 10: the theme page's 48 dp touch targets 10 in, so a 36 dp disc's edge sits 16 in.

## Text roles

Font sizes are sp and scale with the font size; the line box of a row's text is its role's **Latin line box whatever the script** (owner, latinrow): Latin, CJK and mixed text in one role have one height, within the rounding of 1 px (0.5 dp at xhdpi). A code title (honk text) keeps its own box.

| Role | Size, weight, colour | Latin line box (1.0) | Lines | Overflow |
|---|---|---|---|---|
| Row title | 19 sp, semibold, text | 23 | 2 | ellipsis (middle for host:port) |
| Row subtitle | 17 sp, regular, secondary | 20.5 | 3 | ellipsis |
| Value (trailing) | 19 sp, semibold, text | 23 | 1 | ellipsis, never cuts the title |
| Caption, action label | 17 sp, medium or semibold | 24 | 1 | no wrap |
| Section header | 20 sp, bold, text | 24 | 1 | ellipsis |
| Footnote | 15 sp, regular, secondary | 22 | 3 | – |
| Button | 22 sp, semibold | 28 | 1 | – |
| Page title | 32 sp, bold | 38 | 2 | – |

Line breaks: CJK text breaks only between words, never inside one (`rvKeepWords`, owner cjkbreak); names and identifiers (node names, app labels, packages) opt out. Revolut's own boxes are larger (title 26, subtitle 24, §7): we keep ours (latinrow) and the row height is governed by the 58 / 82 minimum.

## Row rules

A row is **one-line** (no subtitle, title on one line), **with subtitle**, or **wrapped** (no subtitle, title on two lines).

| Id | Rule | Value | Source | Check |
|---|---|---|---|---|
| R1 | Row height | ≥ 58 one-line, ≥ 82 with a subtitle; otherwise text block (title, 2 dp, subtitle) + 32; never taller than its text, padding or trailing part need | §7 (148 px pitch; 82 dp 2-line) | gate R1 |
| R4 | Leading visual | 40 dp disc with a 22 dp glyph, a 40 dp flag, or a 40 dp app icon; a notice's disc has a 24 dp glyph; never a bare glyph in a row | owner, iconstyle; Revolut money rows 40 dp (§7), settings bare 24 dp (§12.2) | gate R4 |
| R5 | Leading x | row content starts 16 inside the card; the visual at its start | §7 Leading inset | gate R5 |
| R6 | Text x | 16 after the leading visual (88), or at the row start without one | §7 Leading to text gap | gate R6 |
| R7 | One column per card | rows of one card with the same leading visual start their text at one x | owner 「圖示文字沒對齊」 | gate R7 |
| R8 | Leading, one-line and wrapped row | centred on the row | Revolut: no subtitle, everything centred; D1 for wrapped | gate R8 |
| R9 | Leading, row with subtitle | top = title top (the disc then centres 1.5 dp above the row) | Revolut Appearance, Privacy: icon top = title top | gate R9 |
| R10 | Trailing, one-line and wrapped row | centred | Revolut | gate R10 |
| R11 | Trailing, row with subtitle | switch, radio, check, and every trailing part of a settings row: centred on the title's first line; an in-card button as the disc, top = title top | §13.4 switch row, `07-privacy`, `07-set-accessibility-b` | gate R11 |
| R11l | Trailing, list row with subtitle: value, chip, chevron | centred on the row, or, where the value stands on the title's line (a line-wise row), its top on the title's top | Revolut money and list rows centre the value and chevron (rowalign-1125 "What Revolut does") | gate R11l |
| R11u | Trailing part under the text (a font size at which the title would not read beside it) | its top at the title's bottom (beside the subtitle) or at the text's bottom, its right edge at the content's end | `RvRow` under-end layout (`LocalRvEndSide`) | gate R11u |
| R12 | Title to subtitle | 2 (was 0, line boxes touching, §7) | owner, q-s1125c-titlegap (2026-11-25 18:55); every row with a subtitle: list, settings, notice, skeleton | gate R12; `RvRowRulesTest.aSubtitleTouchingItsTitleBreaksR12` (the old 0 breaks) |
| R13 | Line boxes | one height per role in a golden, within 1 dp | owner, latinrow | gate R13 |
| R14 | Lines | title ≤ 2, subtitle ≤ 3 | `07-set-interface`, `07-privacy` | gate R14 |
| R16 | Trailing x | the part ends at the content's end (16 inside the card) | §13.4 (x 998 of 1039 px) | gate R16 |
| R21 | An end's glyph button (`RvEndMark`, 節點's info mark) | laid out at its 22 dp glyph, 12 after the value it follows; its 48 dp target is placed round the glyph past the layout, so the text loses only the ink and the gap | s1125c: the 48 dp layout box took 26 dp more from the text (`Hong Kong 02 IPLC` wrapped) | `RvEndMarkTest` |
| R17 | Text to trailing | ≥ 12 on one line, to what the part shows: a switch's track, not its node (owner, q-s1125c-switchgap, 2026-11-25 18:55: was 20 to the track, the node's 8 dp room included) | `RvSpace.trailingGap`; no Revolut measure | gate R17 (the switch's node starts at its track); `RvSwitchGapTest` measures the drawn track |
| R18 | Padding | text keeps ≥ 16 above and below, equal when the minimum height governs | §7 | gate R18 |
| R19 | Spacing scale | only S0–S5 | this file | `check-scale.sh`, `check-tokens.sh` |
| R2 | Cards and sections | card on the 16 gutter, 20 radius, 16 apart; section 24 above, 12 below | §2, §13.4 | `RvLayoutTest` |
| R20 | Footnote | one component, `RvFootnote`, on every page; its box `RvSpace.footnoteStart` in from the card above's start edge and `footnoteEnd` in from its end, 12 under it; the values live only in that token pair | owner, q-s1125c-footgap (2026-11-25 18:55; flagged again 19:05 「我感覺向右歪了」, 19:07 「就是這個超出了」, 19:10 「小字備註的啟始 結束位置統一沒做好」; values to follow Revolut's measure) | (a) `rvFootnoteRules` on every golden (each `RvPart.FOOTER` within 0.5 dp of the tokens, every locale and font size; `RvFootnoteTest` plants one on the gutter); (b) `RvFootnoteGateTest`: no page file draws secondary text in the footnote's role except through `RvFootnote` (`rv-footnote-gate.txt` lists the in-card exceptions with reasons); `RvLayoutTest` rulers |

Decided here (2026-11-25), to be confirmed with the package:

- **D1** A wrapped title without a subtitle is a one-line row for R8 and R10: everything centred. Revolut states its rule by "subtitle or not".
- **D2** Our list rows follow Revolut's money rows for display parts (R11l); a switch or in-card control always follows its settings rule (R11), whatever the component.
- **D3** The menu item keeps Revolut's bare 24 dp glyph (UX3 §1): it is a popup, not a row of a card.
- **D4** A card that mixes rows with and without an icon keeps R6 per row (text at 88 and 32), as Revolut's plain list rows do.

## What the gate reads (2026-11-25)

The gate measures the unmerged semantics tree, so it measures what a part draws unless the library tags what it lays out. These readings are part of the rules; each came from a break that was the reading's, not the layout's.

| Reading | Rule it affects | Why |
|---|---|---|
| A row that touches the window's top or bottom edge is **cut**: it goes on past the edge, so R1, R6 (no leading visual left), R8, R10, R11l/R11u, R13 and R18 skip it | those | its middle, height and padding are unknown; the title, the subtitle gap (R12) and the line count (R14) still hold |
| The **trailing room** ([RvRowPart.END], the box `TextBesideEnd` lays an end out in, and a settings row's first-line box) is what R11, R11l, R11u, R16 and the row's height measure; R17 measures what shows | R10, R11*, R16, R1, R18 | a latency figure without its change, or a busy bar standing in for both, keeps the room it takes at rest on purpose (the row does not move when the result arrives) |
| A **line end** (a figure on the title's or subtitle's line) and a **busy bar** are part of the text block; the title's end under the title, on a line of its own, stands between title and subtitle (then the 2 dp) | R1, R12, R18 | RvRow puts an end that does not read well beside its text on a line of its own; R12's 2 dp is for the text, not for that line |
| A busy bar stands where its end stands: centred on the line of a line end, else on the room | R11l, R11u | `BusyAtEnd`, `InRoomOf` |
| A control (switch, radio, check) that went **under** the text stands as any end under it: R11u | R11 | the catalogue's long-subtitle rows |
| A **honk code** title ([RvRowPart.CODE]) keeps the code view's line boxes: no R11 against a role's first line, no R13 | R11, R13 | code is not set in a role |

## Icons (R4 in detail)

| Part | Disc | Glyph | Colour | Source |
|---|---|---|---|---|
| Row icon | 40 dp circle on the raised fill (`discOnCard`) | 22 dp | glyph in the text colour, disc raised | owner iconstyle |
| Flag | 40 dp circle | the flag fills it | ring 8% | iconstyle |
| App icon | 40 dp circle | launcher icon | – | iconstyle |
| Notice | 40 dp circle | 24 dp | as row icon | UX3 §3 |
| Round action | 48 dp | 24 dp | white on glass | §6 |
| Top-bar control | 44 dp | 24 dp | – | §4 |
| Menu item | none | bare 24 dp | text colour | UX3 §1 |

Revolut's settings rows use a bare 24 dp glyph (§7, §12.2). We follow the owner's iconstyle decision, the 40 dp disc, in every row of every page, brand marks included.

## 與規則不符的舊改動

Each earlier change that contradicts a rule. A change goes back to the rule, never to another guess.

| Commit | Pages | What it did | Rule |
|---|---|---|---|
| `87980a14` (rowfirstline, rejected by the owner) | 設定, 回報問題, 關於 and their states | centred the disc on the first line: 7 dp above the title top, 13 dp above Revolut's | R9 (set back in `2f97f227`) |
| library start, `RvRow` | every list page: 活動, 策略, 節點, 依 App, 連線, 組態, sheets, check rows | centres the disc on a row with a subtitle: 1.5 dp low at 1.0, more at larger fonts (1,855 breaks in the before list) | R9 |
| library start, `RvSwitchRow`, `RvCheckRow` | 依 App, 連線, permission and sheet rows | centre the switch or check on a row with a subtitle (528 breaks) | R11 |
| library start, `RvSettingsRow` | 設定, 關於, 回報問題 and their states | a one-line row's title sits at the top of its 26 dp first-line box: 1.5 dp above the centre, while the disc is centred (415 breaks, "above 16 below 19") | R18 |
| library start, `RvBanner` | 活動 notices, 當機, 匯入錯誤, per-app errors | 2 dp between title and reason (`tight / 2`), the one title-to-subtitle gap that is not 0 (113 breaks); set to 0 in `8d01f08a`, then every row got 2 dp (q-s1125c-titlegap) | R12 |
| `cdbc415f` (latinrow) | all rows | gives mixed rows the Latin line box; rows differ by 0.5 dp (1 px) from CJK-only rows | R13 holds within 1 dp; kept |
| node picker (`node-picker-action-failed-*`) | 節點 picker with a failed action | trailing part ends 13 dp short of the row's end | R16 (cause to be read in the code) |

The gate's before list, per golden and rule, is in `rv-row-rules.txt`; it only shrinks.

## Exceptions

None. An exception is added here with its date and review-page id when the owner decides one.
