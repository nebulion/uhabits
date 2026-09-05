# E Ink design notes

This fork restyles Loop Habit Tracker for the E Ink panel of a Mudita
Kompakt-class device. These are the rules it follows, where each came from, and
what is deliberately not done. Check changes against this document.

## Sources, in order of authority

1. **`mudita/MMD` — Mudita Mindful Design** (Apache 2.0).
   The real thing: a Jetpack Compose component library "optimized for E Ink®
   displays on Android". This is Mudita's own design system, and where a
   question can be settled from it, it wins over everything below.
   **Clone it and read it** — `git clone --depth 1 https://github.com/mudita/MMD.git`.
   Its components have behaviour that no screenshot reveals, and this fork has
   twice shipped a bug from inferring it instead (see PROJECT-STATUS on
   `LazyColumnMMD`). The README is explicit that MMD is Material 3 underneath,
   *by design*, so a plain M3 component under `ThemeMMD` is consistent — but
   where MMD ships its own, use it.
   - `mmd-core/.../ColorMMD.kt` — `eInkColorScheme`
   - `mmd-core/.../TypographyMMD.kt` — `eInkTypography`
   - `mmd-core/.../components/` — 40-odd files; the whole component set
2. **`mudita/mmd-migrator`** — a library for moving Material 3 code onto MMD,
   which is precisely the exercise this fork is doing by hand.
3. **The Kompakt itself.** Screenshots of Notes, Settings, Sound, Bluetooth,
   Software Update, Library (e-reader), Weather, Meditation and Calculator,
   captured over adb from a device running MuditaOS K 1.5.0.
4. **Open-source Kompakt apps** — `MuditaOS-K-Calculator-opensource`,
   `-Chess-opensource`, `-Maps-opensource`.
5. **`mudita/MuditaOS`** — the C++/FreeRTOS OS for the *older Mudita Pure*.
   Useful for intent, but a different generation: its `Radius` is 0 where the
   Kompakt rounds its buttons. Treat it as historical.
6. **["Ask HN: sound conventions for e-ink UI development?"](https://news.ycombinator.com/item?id=49213660)**
   — practitioner consensus, which matches MMD closely.

## The tokens

### Colour — black and white, and nothing else

`eInkColorScheme` in `ColorMMD.kt` defines *every* Material role as either
`Color(0xFF000000)` or `Color(0xFFFFFFFF)`. There is no grey in it anywhere,
not even for `error`, `outline`, `scrim` or the disabled states.

So: **all text is black, all backgrounds are white.** Emphasis is inversion —
white on black — never a tint and never a weight of grey.

The trap is that most greys never appear in a layout file. Material supplies
`textColorSecondary`, `textColorHint` and friends itself, so an audit of the
layouts came back clean while the running screens were still full of grey
labels and hints. The theme overrides all of those roles explicitly.

### Where a midtone is unavoidable: halftone, not grey

Charts still need to distinguish an unfilled checkmark from a filled one.
Mudita's answer is a **dot pattern**: the e-reader draws placeholder covers and
its disabled scroll arrow as visible halftones, not as grey fills.

`org.isoron.platform.gui.Halftone` implements this, and both drawing pipelines
use it: `AndroidCanvas.setColor` for the core charts, and `Halftone.apply` in
`ScoreChart`, `StreakChart`, `FrequencyChart`, `TargetChart` and `RingView`,
which have their own `onDraw` and would otherwise send literal greys. Anything
that is not pure black or pure white becomes a 4×4 ordered-dither (Bayer)
pattern of black dots whose density matches the requested luminance — alpha
included, so black at 15% alpha screens like a 15% grey. Text is exempt — a
screened glyph is unreadable — and snaps to the nearer of black or white.

**The dot is one pixel wide, so the screens read as smooth grey rather than as
visible dots.** That is a judged trade against this guideline, not an
oversight: a coarser dot is the more faithful reading, and 3px (~0.36mm here)
was tried on device and rejected — across ninety-odd calendar cells and the
streak bars it turns the page into a dense field of dots. The dither still
earns its keep at 1px, because the panel is handed only pure black and pure
white and never has to dither a midtone itself, which is the thing that ghosts.

### Typography — Lato, and nothing below 14sp

`TypographyMMD.kt` sets the entire scale in **Lato** (SIL OFL). The font files
here are copied from `mmd-core/src/androidMain/res/font/`.

| MMD role | Size |
| --- | --- |
| headlineLarge | 28sp |
| titleLarge | 24sp |
| titleMedium / bodyLarge | 20sp |
| bodyMedium / labelLarge | 18sp |
| titleSmall | 16sp |
| bodySmall / labelMedium | 15sp |
| labelSmall | 14sp |

Two things follow. **Lato is applied app-wide** through the theme's
`fontFamily`, and separately in `AndroidCanvas.setFont`, because the
canvas-drawn charts never inflate a view and so never see the theme.

And **nothing is smaller than 14sp.** This app's scale started at 10sp; those
steps have no counterpart in MMD and were illegible on the panel.

### Shape, weight and dividers

- `DividerDefaultsMMD.Thickness` is **3dp**, solid, `outlineVariant` — which in
  `eInkColorScheme` is black. This is the heavy rule under every screen header
  in Notes, Settings, Library and Calculator.
- List rows are separated by a **dotted** black hairline, indented to align
  with the text rather than running the full width.
- Corners are lightly rounded on buttons and controls. (MuditaOS Pure used 0;
  the Kompakt does not.)
- Elevation is never used. Shadows dither into smears.

### Switch

From `SwitchMMD.kt`, exactly:

| Property | Value |
| --- | --- |
| Track | 52 × 32dp, `RoundedCornerShape(50)` |
| Track outline | 2dp, black in every state |
| Thumb | 20dp circle |
| Off | white track, black thumb |
| On | black track, white thumb |

State is inversion. Material's default — a grey track with a tinted thumb — is
wrong on both counts.

### Buttons

Three levels, consistent across Notes, Calculator and Sound:

1. **Primary** — solid black fill, glyph or label knocked out in white
   (Notes' `+`, Calculator's `=`).
2. **Secondary** — black outline, ~2dp, transparent fill, black label
   (Calculator's operators, Sound's "Advanced").
3. **Tertiary** — bare black text, no enclosing shape (Calculator's digits).

## Structure

Every Mudita screen is: status bar, then a **bold title with a heavy black rule
under it**, then the body, and actions are **always visible in the header** —
Notes puts search, edit and add there; Meditation puts its options glyph there.

**There is no overflow menu anywhere in MuditaOS K.** The three-dot menu and
its floating pane have no Mudita counterpart. The habits list's was replaced
with a direct settings button, and ShowHabit's with header actions. ShowHabit
once kept one survivor -- a developer-only "randomize" behind
`DropdownMenuMMD` -- and that is gone with developer mode itself, so the screen
now keeps the rule exactly. The one remaining exception in the app is the
sort/filter menu on the habits list, which is still an open conflict rather
than a decision; see PROJECT-STATUS.

**A choice is a sheet, not a dialog.** Sound and Bluetooth both raise a bottom
sheet; `ModalBottomSheetMMD` is the component. `AlertDialog` with a
`select_dialog_item` adapter is the Material habit this fork is removing.

Lists show a **permanent scrollbar**: an outlined track, a solid black thumb,
and ▲ / ▼ arrows at the ends. `LazyColumnMMD` draws it, and it is a sibling of
the list rather than padding inside it — present only while the list scrolls.

**Lists page rather than scroll.** Also `LazyColumnMMD`: it disables free
scrolling and steps four items per drag. Anything long therefore has to be real
items, not one tall block.

Subtitles do exist — Bluetooth's "USB Preferences / File transfer" — but they
are **regular-weight black**, never small grey.

## What this app does as a result

**One theme.** No dark mode, no pure-black mode, no night resources,
`forceDarkAllowed` off. A reflective panel has no backlight to dim and a black
page costs a full repaint.

**No habit colour.** The panel cannot render hue. Every habit is black ink; the
picker, the swatch and sort-by-colour are gone. The palette index survives in
the data model so existing databases still load.

**Nothing animates.** Each item below is a run of partial repaints that leaves
a ghost:

| Removed | Where |
| --- | --- |
| Confetti on completion | `ListHabitsScreen.showConfetti` is a no-op |
| List add/remove/move | gone with the RecyclerView; Compose list has no item animation |
| Intro slide cross-fade | `IntroScreenMMD` pages on buttons, no pager |
| Scroll momentum (two paths) | `ScrollableChart.onFling`, `AndroidDataView.onFling` |
| Overscroll stretch and glow | `OVER_SCROLL_NEVER` |
| Ripples | `colorControlHighlight` transparent; MMD disables them globally |
| Window and popup transitions | `windowAnimationStyle` null, fades 0ms |
| Activity cross-fade on restart | `restartWithFade` |
| Scrollbar fade | `fadeScrollbars` false |

Dragging still tracks the finger one-to-one and stops when it stops; what went
is motion that continues *after* the finger lifts.

**Nothing that assumes hardware a Kompakt lacks.** Removed: Help/FAQ (browser),
Rate this app (Play Store), Send feedback (mail), Help translate, View source,
Privacy policy (all browser), and **all six home-screen widgets** plus their
configuration dialogs and the stack-widget service, since the launcher has no
widget host.

**Larger touch targets.** Checkmark buttons 48 → 56dp: a refresh is slow enough
that undoing a mistap is expensive.

## Known gaps

- ~~Lists still scroll continuously.~~ **Resolved, and it came free.**
  `LazyColumnMMD` sets `userScrollEnabled = false` and handles drags itself by
  calling `scrollToItem` in steps of `LazyDefaultsMMD.SCROLL_STEP` (4). Every
  MMD list in the app therefore pages, exactly as MuditaOS `ListView.hpp` does.
  The consequence is that **item granularity is load-bearing** — see
  PROJECT-STATUS.
- **Chart text ignores the system font-size setting.** Views read `dimens.xml`
  in sp; the charts read `Theme.regularTextSize` as a plain double converted by
  `toDp()`. Those same values also drive layout arithmetic
  (`y - theme.smallTextSize * 0.80`), so making them scale needs the charts
  re-laid-out.
- **The widget Kotlin is dead, not deleted.** Unregistered in the manifest and
  unreachable, but `WidgetUpdater` is still called from `ListHabitsActivity`
  and `AutoBackup`, so the classes remain.
- **Settings categories** still render as plain headers rather than MMD's
  drill-down chevron pages.
