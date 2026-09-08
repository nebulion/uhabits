# Project status — Loop Habit Tracker on Mudita Mindful Design

Handoff notes. Read [eink-design.md](eink-design.md) first for the design rules
and their sources; this file covers what is done, what is half-done, and what
is next.

> **There is a second workstream now.** Two-way habit sync over Syncthing, plus
> a terminal client in a new `:uhabits-desktop` module. It is working end to end
> on the device and has its own document: **[sync.md](sync.md)**. It is
> independent of the restyle — it touches no screen and no MMD component — but
> it added two Settings rows and fixed a request-code collision that had
> silently disabled Export, Import and Repair from Settings.

> **Start here:** read [MMD's own gaps](#mmds-own-gaps-verified-in-its-source)
> first. Two of them are live bugs on screens that already look finished, and
> one of them explains a thing you would otherwise spend an afternoon
> disbelieving: **`TopAppBarMMD`'s `showDivider` does not draw the 3dp rule.**

Last verified on a **Mudita Kompakt (`MK20250408317`, MuditaOS K 1.6.0,
Android 12, 480×800 @ 213dpi)**. Debug APK installs and runs.
(`ro.build.display.id` reports 1.6.0; this file said 1.5.0 for a long time.)

## The goal

Restyle the app for the Kompakt's E Ink panel by **using Mudita's own design
system**, not by imitating it.

Two rules, both learned the hard way:

1. **Import MMD components. Never reimplement or approximate them.**
   `com.mudita:MMD:1.0.2` (Apache 2.0, Maven Central) is on the classpath. An
   earlier pass hand-built XML drawables that *looked* like MMD switches and
   headers from device screenshots; they were wrong and have been deleted.
   If MMD has a component, call it.
2. **The end state is Compose-only.** No `res/layout`, no viewBinding, no
   `android.view.View` subclasses of our own.
3. **Read MMD's source before using a component, not just its screenshots.**
   Clone it: `git clone --depth 1 https://github.com/mudita/MMD.git`. Twice now
   a component has behaved in a way that no amount of looking at the screen
   would reveal — see `LazyColumnMMD` under "Known-good details". The AAR on
   the classpath is `com.mudita:MMD:1.0.2`; the source tree is `mmd-core/`.

   Package names are not guessable and do not all match the class name:

   | Component | Package |
   | --- | --- |
   | `TextMMD` | `com.mudita.mmd.components.text` |
   | `TextFieldMMD` | `com.mudita.mmd.components.text_field` |
   | `TimeInputMMD`, `rememberTimeInputMMDState` | `com.mudita.mmd.components.time` |
   | `ButtonMMD`, `OutlinedButtonMMD` | `com.mudita.mmd.components.buttons` |
   | `ModalBottomSheetMMD` | `com.mudita.mmd.components.bottom_sheet` |
   | `CheckboxMMD` | `com.mudita.mmd.components.checkbox` |
   | `CardMMD` | `com.mudita.mmd.components.cards` |
   | `LazyColumnMMD` | `com.mudita.mmd.components.lazy` |
   | `TopAppBarMMD` | `com.mudita.mmd.components.top_app_bar` |
   | `HorizontalDividerMMD` | `com.mudita.mmd.components.divider` |
   | `DropdownMenuMMD` | `com.mudita.mmd.components.menus` |
   | `SwitchMMD` | `com.mudita.mmd.components.switcher` |

   **MMD ships no icon set** — only `chevron_filled_*`, `chevron_dotted_*` and
   a loader. Every other glyph in this app is either a local vector drawable or
   a FontAwesome codepoint. Components not yet used here and worth reaching for
   before hand-rolling anything: `TabsMMD`, `SearchBarMMD`, `RadioButtonMMD`,
   `SliderMMD`, `ChipMMD`, `BadgeMMD`, `SnackbarMMD`, `DatePickerMMD`,
   `NavigationBottomBarMMD`, `TooltipMMD`, `CircularProgressIndicatorMMD`.

## MMD's own gaps, verified in its source

MMD is the authority, but it is not finished, and three of its gaps land
directly on this app. Each was read out of `mmd-core` at commit `0b8940c`, not
inferred from a screenshot.

**`eInkColorScheme` leaves six roles `Color.Unspecified`.** `ColorMMD.kt`
defines every Material role as pure black or white *except* `surfaceBright`,
`surfaceDim`, `surfaceContainer`, `surfaceContainerHigh`,
`surfaceContainerHighest` and `surfaceContainerLowest`. Any component whose
default colours resolve to one of those renders with **no fill at all**. Five
components reach for one:

| Component | Property | Resolves to | Live here? |
| --- | --- | --- | --- |
| `TextFieldMMD` | container, *all four states* | `surfaceContainerHighest` | **Yes** — every field on EditHabit |
| `TimeInputMMD` | `timeSelectorUnselectedContainerColor` | `surfaceContainerHighest` | **Yes** — the reminder sheet |
| `CardMMD` | container + content, incl. disabled | `surfaceContainerHighest` | No — landmine |
| `TopAppBarMMD` | `scrolledContainerColor` | `surfaceContainer` | No — only if a `scrollBehavior` is ever passed |
| `SearchBarMMD` | container | `surfaceContainerHigh` | No — landmine |

The two live ones are visible: the reminder sheet's *unselected* hour/minute
box has no box at all — confirmed on device, it is bare digits next to a
solid black selected field — and every text field on EditHabit is a floating
label over an underline with nothing behind it. That second one currently
*looks* right only because the page behind it is already white. It is a
coincidence, not a token. Fix by passing an explicit container colour through
each component's own `colors()`/`Defaults` factory.

**`TopAppBarMMD.showDivider` cannot draw the 3dp rule.** It calls raw
Material3 `HorizontalDivider` — not `HorizontalDividerMMD` — and passes no
`thickness`, so it falls back to Material's **1dp**. Its own
`TopAppBarDefaultsMMD.dividerLineHeight` of 3dp is applied as a `.width()`,
which cannot affect a horizontal line's thickness. So every header rule in
this app was a hairline while the docs claimed it was the heavy rule.
**Fixed**: all five bars now pass `showDivider = false` and the caller draws
`HorizontalDividerMMD()` under them, whose default thickness really is
`DividerDefaultsMMD.Thickness` = 3dp.

**MMD ships no dotted divider.** `eink-design.md` calls for a dotted hairline
between list rows. `HorizontalDividerMMD` can only draw solid, and the
`DashedDivider()` that `MenuMMD.kt`'s own KDoc calls **is not defined anywhere
in the library** — it is a dead reference in Mudita's docs. Every row divider
here is therefore solid by necessity, not by choice.

**MMD has no dialog, and no dense text field.** There is no alert/confirm
component at all, and `TextFieldMMD` has one shape: `MinWidth` 280dp,
`MinHeight` 56dp, 16dp padding on all four sides. There is no compact variant
to put inline inside a sentence.

## Where things stand

| | |
| --- | --- |
| Layout files | **5** (from 45) — target 0 |
| viewBinding | still `true`, and only two cards keep it alive |
| Screens on MMD | Settings, Habits list, EditHabit, ShowHabit, Snooze |
| Popups on MMD | Checkmark, Number, Confirm-delete, Frequency, Reminder, Weekdays, Snackbar, HabitType |
| Still on Views | the score and bar charts, and Automation |
| The 5 layouts | `show_habit.xml` + `show_habit_score.xml` + `show_habit_bar.xml` (the last two charts), `automation.xml` + `toolbar.xml` (the Tasker plugin, and a toolbar nothing else inflates) |

### Done

**Foundation.** Compose enabled, `com.mudita:MMD:1.0.2` resolving. Opt-in for
`ExperimentalMaterial3Api` is a compiler arg in
`uhabits-android/build.gradle.kts`, not per-class — annotating classes leaked
the annotation into unrelated callers.

**Design tokens, all traced to MMD source.** Lato copied from
`mmd-core/src/androidMain/res/font/`; the type scale in `dimens.xml` re-derived
from `TypographyMMD.kt` (nothing below 14sp); `eInkColorScheme` is literally
black and white for *every* token, which is where the zero-grey rule comes
from.

**Settings** — fully MMD. `TopAppBarMMD` (with `showDivider = false` and an
explicit `HorizontalDividerMMD` under it — see MMD's own gaps above),
`SwitchMMD`, `HorizontalDividerMMD`, `TextMMD`, `LazyColumnMMD`, under
`ThemeMMD`. `SwitchMMD` was checked against its source line by line: 52×32dp
track, 20dp thumb, 2dp outline, inversion for state. Correct on device.

**Habits list** — now MMD. `HabitCardListAdapter` was split: everything that
was not RecyclerView plumbing moved to `HabitCardListModel`, a plain state
holder exposing `StateFlow<List<HabitCardUiState>>`, and the two
`…Behavior.Adapter` bindings in `ListHabitsModule` point at it. The screen is
`HabitListScreenMMD.kt`.

Its grid is laid out by **weight, not by fixed cell width**: the heading row
and the habit rows both open with a 140dp name column and then give every day
column `weight(1f)`, so the columns fill the width instead of packing left and
leaving dead space against the scrollbar. Only the heading row applies
`SCROLLBAR_GUTTER` — the habit rows are inside `LazyColumnMMD` and get it from
its `contentPadding`. Adding a second gutter there shrinks the weighted cells
and pulls them out of line with the headings, which is exactly the bug that was
there before.

**Selection is back.** Long-press still toggles selection, but the actions now
live in a selection variant of `TopAppBarMMD` (`SelectionTopBar` in
`HabitListScreenMMD.kt`) driven by `HabitCardListModel.selectedIds`: a count, a
cancel, then edit / archive / unarchive / delete, each shown according to
`SelectionCapabilities`, which is read straight off
`ListHabitsSelectionMenuBehavior`. A `BackHandler` clears the selection before
back leaves the screen. `ListHabitsSelectionMenu` and its `ActionMode` are
deleted, and with them the `Lazy<>` cycle through `HabitCardListController`.

**Intro is deleted**, and was MMD before that. It is recorded here only
because the work is a useful warning: the deck was ported to MMD, and its three
illustrations had to be re-derived from the originals in the
`original-intro-icons` folder of the `uhabits-reset-2026-08-17` backup, because
an earlier e-ink pass had whitened their coloured ink into blank rectangles.
Thresholding at 240, cropping each above the drop shadow the threshold turned
into a black smear, closing one card border by hand. All of it now deleted,
because the screen explained what a habit tracker is to someone who had just
installed a habit tracker. **Check whether a screen should exist before making
it beautiful.**

**ShowHabit** — MMD shell, Views inside. The header is `TopAppBarMMD`, the
scroll is `LazyColumnMMD` and each of the nine cards is a real `CardMMD`
(8dp corners, 3dp black border, zero elevation). What is left of the old screen
is the *contents* of those cards, hosted one per lazy item through
`AndroidView`, because the charts still draw through their own `onDraw`. The
AppCompat `Toolbar`, the `ScrollView` and the overflow menu are gone, and with
them Android's translucent grey scrollbar down the right edge.

**A halftone dot is one device pixel** (`Halftone.DOT`), which means the
screens read as smooth grey rather than as visible dots. This is a judged
trade, not an oversight. A coarser dot is the more faithful reading of Mudita's
guideline -- their placeholders and disabled scroll arrow are visibly dotted --
and it was tried at 3px (about 0.36mm) on the device: across ninety-odd
calendar cells and the streak bars it turns the page into a dense field of dots
and reads worse than the grey. Reverted by preference. The dither still earns
its place at 1px, because the panel is handed only pure black and pure white
and so never has to dither a midtone itself, which is the thing that ghosts.

**Cards are separated, not boxed.** `CardMMD` exists and is correct MMD, but
nine of them stacked put a 3dp border around every block on a page that is
mostly chart. MMD's other structural rule -- a divider between blocks, which is
what `SettingsScreenMMD` and MuditaOS Settings both use -- is the right one
here, so ShowHabit uses `HorizontalDividerMMD` between items.

**The View-drawn charts screen their own midtones now.** `AndroidCanvas`'s
Bayer dither moved out to `org.isoron.platform.gui.Halftone`, and
`ScoreChart`, `StreakChart`, `FrequencyChart`, `TargetChart` and `RingView`
route every `paint.color =` through `Halftone.apply`. They never touched
`AndroidCanvas`, so until now their greys — `contrast20` gridlines at #BDBDBD,
and the ring's inactive arc as black at 15% alpha — reached the panel as
literal greys. `Halftone.apply` is a no-op for pure black and white, and it
also clears a stale shader, which matters on paints reused for both a screened
fill and solid text.

**The grey scrim is gone from every popup, and that was the biggest single
visual violation left in the app.** Android dims the screen behind any dialog
by default — a flat mid-grey over the header, the Save button, everything —
and every dialog here had it. `ModalBottomSheetMMD` refuses it by defaulting
`ScrimColor` to `Color.Transparent`; everything else now clears `dimAmount`
explicitly. There was already an unused `Theme.Transparent` style in
`styles.xml` carrying `backgroundDimEnabled=false`: the fix had been worked
out once and never applied.

**Popups are one shared MMD panel now.** `PanelMMD` and
`MmdPanelDialogFragment` in `activities/common/dialogs/`. MMD **ships no
dialog** — all twenty-two component packages were read, there is
`ModalBottomSheetMMD` and nothing else that floats over a screen — so the
container is ours, but everything about it is MMD: white fill, 3dp black
border (`DividerDefaultsMMD.Thickness`), 8dp corners
(`ButtonDefaultsMMD.buttonCornerRadius`), zero elevation, no dim. Having
exactly one of it is the point — the checkmark editor, the number editor and
the delete confirmation are the same object with different contents.

On it: **`CheckmarkDialog`**, **`NumberDialog`** and **`ConfirmDeleteDialog`**.
The first two shared an XML layout whose border and button divider were
`?contrast40` — literally `#BDBDBD` — with two buttons tinted `contrast60`
grey. That layout and both grey drawables are deleted. States are now told
apart by fill, MMD's way: the committing action (Yes / Save / Delete) is a
solid `ButtonMMD`, everything beside it an outlined `OutlinedButtonMMD`.
`NumberDialog` parses with `toDoubleOrNull`, not `toDouble` — see the crash
under [Untested](#untested--verify-these-before-building-on-them).

**The boolean frequency picker is a page, not a popup**, and it was the last
`AlertDialog` a screen raised. `FrequencyPickerDialog` and
`frequency_picker_dialog.xml` are deleted; it is `FrequencyPage` in
`EditHabitScreenMMD.kt`. A sheet was built first and is right for a *list* —
Sound and Bluetooth both raise one — but this is the only picker here that is
a *form*, and MuditaOS answers a form by drilling into a page with its own
header. Two things fell out of that: the keyboard can no longer cover the
commit button, because Done lives in the header; and there is room to lay the
fields out properly.

- **The numbers are edited below the list, not inside it.** "Every `[3]` days"
  cannot be made to line up: `TextFieldMMD` is 56dp tall with 16dp padding and
  a bottom indicator, so its digits never share a baseline with the words
  beside them, and because each sentence puts its `%d` somewhere different the
  fields land in a different column on every row. Each row is a plain sentence
  with its value read into it; only the selected row opens a field, full
  width and labelled.
- **An unfilled number reads as `X`, not as a default.** Only the shape the
  habit already uses arrives with a value; the rest say "X times per week", so
  no row claims a choice that was never made.

Its row shape is MMD's own, from the usage sample in `RadioButtonMMD.kt`: 56dp
row, an **inert** `RadioButtonMMD` (`onClick = null`, the row carries the
selection), label at `padding(start = 16.dp)`, `bodyLarge`. Note an inert
`RadioButtonMMD` measures **24dp**, not 48dp — it only takes Material's
interactive minimum when given its own `onClick` — which is why the text inset
is 24 + 16.

**The sheets that remain are static.** `skipPartiallyExpanded = true` and
`dragHandle = null` on all of them. Without the first, a sheet taller than
half the screen opens at half height with its button below the fold, which is
what the weekday sheet was doing. Without the second it advertises a drag that
does nothing.

**ShowHabit's header actions are full size again.** They had been shrunk to
40dp buttons to fit five actions and a back arrow across 320dp. That is the
wrong trade on a panel where a mistap costs a full repaint, so the buttons are
back to the 48dp minimum and the *count* gave way instead: **export is gone
from the header, and is currently unreachable** — it needs a home.

**EditHabit** — now MMD, in `EditHabitScreenMMD.kt`. It was a `ScrollView` of
Material `TextInputLayout` boxes: every field an outlined rectangle with its
label notched into the top edge, every picker a row with a Material spinner
triangle. Now the fields are `TextFieldMMD`, the choices are
`ModalBottomSheetMMD`, the weekday picker is `CheckboxMMD` rows, and the
reminder is `TimeInputMMD` — which replaces the third-party
`com.android.datetimepicker` radial dial, a drag gesture on a panel that
repaints slowly. `EditHabitActivity` keeps the habit, the validation and the
commands; the form's text lives in Compose state.

**Everything animated is off**, and **nothing assumes a browser, a store or a
widget host** — all six app widgets are unregistered from the manifest.

**ShowHabit is a summary over tabs now, not nine stacked cards.**

It was a `LazyColumnMMD` of nine cards, and two things were wrong with that on
the device. Nothing read at a glance -- the figures you check daily were the
third card down -- and **the scrollbar visibly changed size as you dragged**.
That second one is worth writing down because it looks like a bug in this app
and is not: `LazyMMD.kt:431` sizes the thumb as `visibleCount /
totalItemsCount`. For a list of like-sized rows that is right, which is why the
habits list is fine. For nine cards ranging from a two-line subtitle to a
full-height chart, `visibleCount` swings between one and three as you move, and
the thumb resizes with it. It was a list component doing a document's job.

So the summary is hoisted out and pinned -- score, best streak, month delta,
total, always visible -- and the rest sits behind `SecondaryTabRowMMD`:
**Calendar, Score, Streaks**. MMD's own documentation describes the secondary
row as "used within a content area to further separate related content", which
is this exactly, and its 3dp indicator is already the weight of every rule in
the app. It is also one of the few MMD components whose defaults touch none of
the six unfilled colour roles, so it needs no override.

Two things about the tabs are decisions rather than mechanics. There were four
and there are three, because the row divides the width evenly and MMD takes
16dp off each side of a tab, so four left 58dp for a label and "Calendar"
rendered as "Calend". And the one that went was **Notes**, which held the
question, the frequency, the reminder and the description -- every one of them
a field on the edit screen, one tap away, where they can also be changed.

**The calendar is real Compose**, in `HabitCalendarMMD.kt`, and it is the one
card that never needed the chart port to stop being a View: `HistoryCardState`
is already a plain list of `HistoryChart.Square` with nothing Android in it.
Done is a filled square, skipped is an outline holding a smaller one, short of
target smaller again, and not-done an empty outline -- fill, not grey, which is
the same language the habits list uses. `series[0]` is today and the list runs
backwards, so the index maths is written out and explained rather than inlined;
it was verified against the panel by reading pixels, not by eye.

**Everything else on that screen is still a hosted View**, one per lazy item,
inside panels that use `LazyColumnMMD(isScrollbarVisible = false)` -- a thumb
sized by item count says nothing useful about two cards of unequal height. Each
panel becomes plain Compose as its charts are ported.

**The startup screen is now nothing at all.** Android 12 draws one whether the
app asks or not and builds it from the launcher icon, so it cannot be removed
-- only filled in. It is given the same white the app opens on and an empty
icon drawable, and `ListHabitsActivity.holdSplashUntilDrawn()` refuses the
first draws until the list has been measured with content in it. So the app
appears in one piece instead of the header, then the headings, then the rows
each landing as its own partial repaint. The hold releases on whichever comes
first, content or `SPLASH_HOLD_MS`: waiting only on habits hangs forever on a
fresh install, and a hold with no ceiling reads as a crash.

**The launcher icon is the app's own loop mark in MuditaOS's frame** -- a
rounded square outlined in one black stroke, holding the loop lifted from
`ic_notification.xml`. It was five densities of PNG with a drop shadow baked
in, which is what made both the launcher and the startup screen show a grey
smear. **Its stroke weight is not right yet** -- next to Weather and Calculator
on the home screen it reads heavier than the house style, and the corner radius
may be off too. Mudita publishes no icon-drawing guideline in MMD (the library
ships no icon set at all), so this needs either their design documentation or
measurements taken off the system icons themselves.

**Delete was silently broken, and it was not the dialog.** A regression from
the `HabitCardListAdapter` -> `HabitCardListModel` split, found by checking the
database rather than the screen. `ListHabitsSelectionMenuBehavior.onDeleteHabits`
called `adapter.getSelected()` **twice**, side by side:

```kotlin
adapter.performRemove(adapter.getSelected())
commandRunner.run(DeleteHabitsCommand(habitList, adapter.getSelected()))
```

`HabitCardListModel.getSelected()` filters `_habits`, the *displayed* list, and
`performRemove` drops those habits from the cache synchronously. So by the
second call the selection was empty and the command was handed nothing. The
confirmation appeared, the row vanished, and the habit came back on the next
refresh. The old RecyclerView adapter did not rebuild synchronously, which is
why the double call used to work. Now captured once.

**Messages are `SnackbarMMD`.** They were a Material `Snackbar` -- a dark grey
card with an elevation shadow -- reachable from a live path whenever a CSV
import or export succeeded or failed. `ListHabitsScreen` posts to Compose state
and `HabitListScreenMMD` shows it in a `SnackbarHostMMD`.

Its colours are all stated rather than defaulted, because two of MMD's own
defaults are wrong under this theme. **`actionContentColor` is `inversePrimary`,
which `eInkColorScheme` defines as white -- on a container it also defines as
white**, so an action label would have been invisible. And `SnackbarMMD`'s
divider is the `TopAppBarMMD` bug again: 3dp handed to a raw Material3
`HorizontalDivider` as a *width*, no thickness passed, so it renders at 1dp.
MMD's published anatomy for this component wants two stacked rules, a black one
over a white one, so `MessageBar` draws both itself at the real
`DividerDefaultsMMD.Thickness`.

**The numeric target no longer crashes the app.** Confirmed on device before
and after: typing `1.2.3` into a measurable habit's target and pressing Save
threw `NumberFormatException` out of `EditHabitActivity.save()`. `validate()`
only checked `isBlank`, and `KeyboardType.Number` is a hint about which
keyboard to raise -- it filters nothing. Fixed in three places: `validate()`
parses, `save()` uses `toDoubleOrNull`, and the field keeps only digits and one
separator as you type. Comma decimals (`1,5`) parse now too; they would have
crashed identically in a comma locale.

**EditHabit's rows line up.** `TextFieldMMD` insets its own label by 16dp and
`PickerRow` put its text straight into the row, so field labels sat at 32dp
from the edge and picker labels at 16dp -- two columns that did not agree.
Measured in the UI dumps (42px against 21px) before touching it.

**ShowHabit's header actions are full size.** The buttons were already 48dp;
the *glyph* inside was Material's default 24dp, which is 12dp of nothing on
every side and reads small beside a 24sp title. Glyphs are 32dp now, which
costs no layout at all. Export left the header to make room and **is currently
unreachable** -- it needs a home.

**Ghosting has a deliberate flash.** `EinkRefresh` puts an opaque black view
over the window for 100ms, which is InkOS's exact mechanism and duration. It
counts *commands and scrolls* -- one command is one thing the owner did, and
`LazyColumnMMD` pages by item so a scroll is already discrete. Off by default,
behind **Settings -> Flash screen to clear ghosting**, **interval 12**. Written
but **not yet seen to fire**; see "Clearing ghosting" for why it is a heuristic
and why the `meink` service was not used. The interval was 5, which interrupts
a run of checkmark taps: the flash is itself a full repaint and the most
intrusive thing the app does, so it is now spent about half as often.

**Developer mode is gone**, and with it the last dropdown on ShowHabit. It was
a Settings toggle gating a `DropdownMenuMMD` holding "randomize" -- which
rewrites five years of a habit's entries with random data, on a device whose
only copy of the owner's 41 habits lives in that database. Removed: the
`isDeveloper` preference, `ShowHabitMenuPresenter.onRandomize`, the dropdown
and its `DeveloperMenu`, the Settings row, and the five-tap easter egg on the
About screen's version number. `ShowHabitMenu` and `res/menu/show_habit.xml`
went with it -- both were already dead, never constructed since the header
became `TopAppBarMMD`. **`AboutScreen` is deleted too**: the countdown was the
only thing left in it, so `AboutView` now takes no screen at all.

This also settles one of the two conflicts the audits flagged (below): the
overflow-menu rule now has exactly one exception left, the sort/filter menu on
the habits list, not two.

**Settings pages at the same speed as the habits list.** `LazyColumnMMD` does
not scroll freely -- it steps `scrollStep` items per drag -- and Settings was
using the default of 4 where the habits list passes 6. It was the slower screen
to get through of the two despite having the taller rows (64dp against 56dp);
both are 6 now.

**The stores cannot replace this build.** See below.

### Half-done — read before touching the habits screen

- **Drag-to-reorder: built, rebuilt, and awaiting one human check.**
  Long press a habit to select it, long press again to pick it up, drag,
  release. The list holds still while you drag and the separator you would
  drop into thickens; the reorder happens once, on release. Holding at the top
  or bottom edge turns a page every second.

  **The gesture is owned by a container around the list, not by a row**, and
  four separate bugs are the reason. Anyone touching this should read them
  before "simplifying" it back:

  1. `positionChange()` reports a movement as **zero** once the change has been
     consumed. Consuming before reading silently zeroed every drag: the habit
     picked up and then never travelled. Read first, consume second — or use
     `positionChangeIgnoreConsumed`.
  2. `LazyColumnMMD` claims drags for its own paging. `verticalDrag` and
     `detectDragGesturesAfterLongPress` both treat a claimed movement as a
     *cancelled* drag and give up, so neither works here. The gesture runs in
     the **`Initial` pointer pass**, where a parent sees events before its
     children, and consumes only once a drag is actually running — so taps,
     long presses and the list's own scrolling are untouched.
  3. Reading the finger as `thatRow.offset + local y` broke the moment a page
     turned: the row left `visibleItemsInfo`, the lookup returned nothing, and
     no boundary could be computed again — you could scroll but not place.
     Worse, a `LazyColumn` may **dispose** a row it has scrolled past,
     destroying the node hosting the gesture. A container is never disposed and
     its coordinates are the list's own.
  4. Folding tap, long press and drag into a single detector — tried, reverted.
     It stopped the name column receiving any pointer events at all, taps
     included. `combinedClickable` keeps tap and long-press-to-select; the drag
     detector is simply not armed until a habit is selected, so at any moment
     exactly one of them wants the long press.

  Still open:

  - **The list scrolls when you release.** Not the page turn — that is
    cancelled on release — but `LazyColumn` re-anchoring: it keeps the keyed
    first-visible item in view, and dropping a habit moves that item. The fix
    is to snapshot `firstVisibleItemIndex`/`firstVisibleItemScrollOffset`
    before the drop and restore them once the list has rebuilt.
  - **It only works in manual order** (`BY_POSITION`), silently. Sorted by name
    or score the habit picks up and the drop does nothing, with no explanation
    anywhere.
  - **The page-turn case has never been confirmed working by a human.** It
    cannot be driven over adb at all — see "Verified on the device".

- **`ScrollableChart` cannot be deleted yet** — it is still the base class for
  `ScoreChart` and `FrequencyChart` on the ShowHabit screen.
- **Drag-to-reorder is gone.** `performReorder` survives on the model;
  no UI calls it. Plan was long-press → move up / move down.
- **There are no UI tests for the habits screen.** The whole Espresso suite
  drove the old View classes and the overflow menu that MuditaOS has no
  counterpart for, so it was deleted rather than half-fixed: the `acceptance/`
  tree, `ListHabitsRegressionTest`, `SavedStateTest`, the
  `activities/habits/list/views/` tests with their `render.png` baselines, and
  `HabitsActivityTestComponent`. `BaseUserInterfaceTest` was **kept** although
  nothing uses it — it still sets up the component, fixtures and `UiDevice`,
  which is the scaffolding a Compose rewrite wants. Anything written back
  should use `createAndroidComposeRule`, not `withClassName`.
  A copy of everything deleted is in
  `C:\Users\Antonio\Backups\uhabits-deleted-views-2026-08-17\`.
- **Two classes are misnamed and load-bearing.** `WidgetReceiver` survives the
  widgets' deletion because the reminder *notification* action buttons use its
  intents, and `WidgetPreferences` survives because `ReminderScheduler` keeps
  snooze times in it. Neither has anything to do with home-screen widgets any
  more. Check before deleting anything matching `*Widget*`.

### Known-good details worth not breaking

- **Checkmark index 0 is today.** `EntryList.getByInterval` walks backwards.
  Day headings run oldest-first, so the cell lookup flips the index:
  `(dayLabels.size - 1 - index) + dataOffset`. Getting this wrong mirrors every
  habit's history and is not obvious on screen.
- **Unknown draws as a hollow circle**, and only when `areQuestionMarksEnabled`;
  otherwise it draws as `✗`. Keep the gate. The circle replaced a `?`, which
  was correct but shouted — most cells in an imported database are unknown.
- **The bundled FontAwesome is a subset (1001 glyphs), and most of
  `fontawesome.xml` is commented out for that reason.** Uncommenting a line
  does not mean the glyph exists: `fa_trash_o` (f014) is absent and renders as
  nothing at all, with no error — an action button silently disappears. `fa_trash`
  (f1f8) is present. Check with `fontTools` before relying on a codepoint.
  The hollow *outline* variants (`circle_o`, `square_o`, `circle_thin`) are all
  absent; hollow shapes come from drawing a solid glyph with
  `drawStyle = Stroke`, which is what `GlyphText(hollow = true)` does.
- **`SCROLLBAR_GUTTER = 40dp`, and it is conditional.** Read `LazyMMD.kt`
  before touching this. The scrollbar is **not** content padding
  (`LazyDefaultsMMD.contentPadding` is `PaddingValues(0.dp)`): the list sits in
  a `Row` with `weight(1f)` next to a scrollbar composed **only while
  `canScrollBackward || canScrollForward`**. It is 24dp of arrow
  (`navigateIconSize`) inside 8dp of padding either side. So the rows are 40dp
  narrower when the list scrolls and full width when it does not, and the day
  heading — which is outside the list — has to follow, which is why it takes a
  `scrollbarGutter` derived from a hoisted `LazyListState` rather than a
  constant. Hard-coding it looks right only until the list is short enough not
  to scroll.
- **`LazyColumnMMD` pages, and it pages by item.** It sets
  `userScrollEnabled = false` and handles drags itself by calling
  `scrollToItem` in steps of `LazyDefaultsMMD.SCROLL_STEP` (4); the scrollbar
  thumb is sized from `totalItemsCount`, not from pixels. Anything hosted as
  one tall item therefore **cannot be scrolled at all** — every `scrollToItem`
  clamps to index 0 — and a card hidden with `visibility = GONE` still counts
  towards the thumb and the paging. Emit real items, and drop hidden ones from
  the list rather than collapsing them. (This is also the answer to the
  "lists still scroll continuously" gap the design notes used to list: they
  don't.)
- **3 day columns, sized by weight.** 140dp name column, then the remaining
  ~180dp of the 320dp content area split three ways. Four columns fit
  arithmetically but leave too little for a name, and the headings start to
  wrap.
- **Disabled pager arrows use `mmd_chevron_dotted_*`**, not a grey solid one.
- `threshold` is `targetValue / frequency.denominator`, not `targetValue`.
- `isShortToggleEnabled` swaps *which gesture* toggles; it does not change the
  cycle. Numerical habits have no toggle path at all.

## Keeping the stores off this build

Both **F-Droid** (`org.fdroid.fdroid`) and **Aurora Store** (`com.aurora.store`)
are installed on the Kompakt, and this fork was carrying upstream's exact
`applicationId` (`org.isoron.uhabits`) and `versionCode` (20301). To either
store that is simply Loop Habit Tracker 2.3.1 with an update pending. There are
three defences, and it is worth knowing which ones are real:

1. **`versionCode` is now 1000000** (`versionName` `2.3.1-mmd`). Upstream
   numbers releases `major*10000 + minor*100 + patch`, so even 99.99.99 only
   reaches 999999. Both stores decide "update available" by comparing their
   index's versionCode against the installed one, so neither will ever offer
   one. **This is the change that was made.**
2. **The signature already blocks it.** This is a debug build, signed with the
   Android debug key; every store's APK is signed with a different one, so
   Android would reject the install outright rather than overwrite the app.
   This defence is free and was already in place -- but it fails silently as a
   *failed install*, not as "no update offered", which is why defence 1 is
   worth having.
3. **Not done, and it is the only complete one:** changing `applicationId` (to
   e.g. `org.isoron.uhabits.mmd`) makes the fork invisible to both stores
   rather than merely unattractive. It is not done because a new package id
   means a new data directory, and the owner's 41 habits with years of entries
   would have to be migrated across by hand. Take this step only with a
   verified database copy in hand.

Neither 1 nor 2 stops a **manual** "install anyway" from a store, and neither
survives an uninstall. If the app ever does disappear, reinstall from
`assembleDebug` rather than from a store.

## Clearing ghosting: what the device actually offers

Researched, not yet built. The short version: **there is no one-shot "refresh
now" API for a normal app, and the thing that actually works is a full-screen
black flash.**

What is really on the Kompakt:

- A live system service, `meink` (`android.meink.IMeinkService`), and a
  root-only sysfs tree at `/sys/einkinfo/` carrying `waveform_mode`,
  `refresh_mode`, `clear` and friends. `shell` cannot read it; SELinux labels
  the service `mudita_service` and whether an ordinary `untrusted_app` may
  reach it **was not established** — that needs a throwaway APK to answer.
- Two undocumented framework jars are installed and declarable as libraries:
  `/system/framework/kompakt-sdk.jar` and `mudita-sdk.jar`. Decompiled,
  `kompakt-sdk.jar` holds `android.meink.MeinkManager` with five methods, plus
  `RefreshMode` (`AUTO/FAST/FAST_DITHER/QUALITY/TEXT`) and `WaveformMode`
  (`A2/AUTO/DU/GC16/GL16/INIT`). **Every method sets a persistent display
  mode. None of them is "flash the panel now."** There is no public Mudita
  documentation, Maven artifact or sample for either jar, and MMD itself
  contains no EPD call of any kind.
- No Onyx/BOOX SDK on this device — that avenue is closed, not merely unused.

**InkOS** (`com.github.gezimos.inkos`, GPLv3, already installed on this
device) is the app that has solved this on this hardware, and its source
settles it. It reaches the same `meink` service by raw Binder reflection — but
only to set those same persistent modes. Its actual ghost-clearing is
`EinkRefreshHelper`: add an opaque full-screen `View` over the window, hold it
~100ms, remove it. Every device branch it has — Onyx, Hisense, Bigme, Dasung,
Kompakt — funnels to that same overlay. Its default delay is 100ms (range
25–1500), and it is **off by default**, fired on app-exit rather than on a
change counter. So there is no established "every N changes" cadence to copy;
that would be ours.

Honest assessment of the flash: it is a heuristic, not a real full-refresh
command. It works because a full-screen maximum-delta repaint pushes most EPD
drivers into picking a fuller waveform themselves — which this app can neither
inspect nor force. It reliably reduces visible ghosting in practice, which is
why every e-ink launcher ships it, and it costs a visible flash plus one
composite pass. It needs no permission, no hidden API and no `uses-library`,
so it keeps working across OS updates.

Recommendation: build the overlay flash, behind a user-facing toggle and
interval. Do **not** build on `MeinkManager` or the `meink` Binder — neither
offers the primitive we want, and app-level access is unverified.

Both jars, the dexdump, and InkOS's `EinkRefreshHelper.kt` are pulled into the
scratchpad noted at the end of this file.

## Verified on the device

Everything in this section was exercised on `MK20250408317` against a
throwaway habit and checked in the database, not on screen. **Keep doing it
that way**: the panel repaints slowly enough that a screenshot taken after an
`input tap` often shows the previous screen, and an action that silently did
nothing looks identical to one that worked.

```bash
C:\Users\Antonio\android-sdk\platform-tools\adb.exe -s MK20250408317 exec-out "run-as org.isoron.uhabits cat databases/uhabits.db" > uh.db
```

**Test on a throwaway habit, never on the real ones.** The database is the
owner's actual habits with years of entries and there is no copy in the repo.
Create one called `zzz test`, do the destructive checks on it, delete it after.
An entry set back to UNKNOWN stores `value = -1`, which renders and behaves
exactly like no row at all — that is the safe way to undo a mis-tapped
checkmark.

Passing as of the last pass:

- **EditHabit** — create writes the right row; editing an existing habit's
  question persists and survives reopening; saving with a blank name shows
  "Cannot be blank" under the field and stays on the screen rather than
  crashing.
- **Selection actions** — archive sets the flag and the habit leaves the
  default filter; the bar swaps Archive for Unarchive when an archived habit is
  selected; unarchive clears the flag; delete asks first and then deletes.
  There are no move-up/move-down buttons, which is correct — dragging replaced
  them.
- **Export** — opens the file manager and writes a real file (10,000 bytes for
  one habit). The old worry about "a device with no mail client" is moot: it no
  longer uses the share sheet at all.

**A trap that cost a wrong conclusion once.** There were two habits called
`zzz test` — one left over from an earlier session — and scrolling found the
older one first, so an unarchive appeared to fail when it had actually worked
on the wrong habit. If a check surprises you, confirm *which row* you acted on
by id in the database before believing it.

Still not verified, and both need a human:

- **Drag-to-reorder across a page turn.** `adb input motionevent` delivers each
  injection as a fresh pointer, so the gesture's own `down.id` never matches
  and the drag never starts under automation. It has to be tried by hand.
- **The ghosting flash firing.** Written, enabled in Settings, and still never
  observed. It is the one feature here whose whole value is empirical.

**`Log.d` does not reach `logcat` on this device.** Debug logging added during
both the drag work and the export work produced nothing at all, twice, while
the code around it demonstrably ran. Reason from outcomes — a file's size, a
row in the database — rather than from traces, and do not spend time
disbelieving working code because the log is silent.

## What is next

Ranked by what it costs the owner, not by what it costs to build.

1. **Confirm drag-to-reorder survives a page turn.** Rebuilt so the gesture is
   owned by a container around the list rather than by the dragged row, which
   is what broke it: the row scrolled out of view mid-drag, took its layout
   entry with it, and no drop boundary could be computed again. It cannot be
   tested over adb (see "Verified on the device"), so this needs a finger.
2. **See the ghosting flash fire.** Enabled, interval 12, never observed.
3. **The two remaining charts** — `ScoreChart` and `BarChart`. These are the
   only thing keeping `viewBinding` alive and the only Views left on ShowHabit.
   They are also the hardest piece in the app, because both are interactive:
   dragging them scrolls back through history via `ScrollableChart`, and that
   has to be rebuilt in Compose without losing the ability to look back.
4. **Decide on Automation** (the Tasker/Locale plugin). It is registered and
   would work, but **no Tasker, Locale, Automate or MacroDroid is installed on
   the device**, so today it advertises a capability nothing can use. Deleting
   it takes `automation.xml` and `toolbar.xml` with it -- nothing else in the
   app inflates a toolbar any more -- leaving only the two chart layouts. This
   is a judgement call about a real feature, not dead-code removal, so it wants
   the owner's word rather than a cleanup pass.
5. **The launcher icon's stroke weight.** The mark and the frame are right; the
   weight is not. Next to Weather and Calculator it reads heavier than the
   house style, and the 16-unit corner radius may be off too. MMD ships no icon
   set and no drawing guideline, so this needs Mudita's design documentation or
   measurements taken off the system icons themselves.
6. **`ktlintCheck` is red** — ~287 violations, nearly all trailing commas.
   `ktlintFormat` fixes it mechanically but rewrites most of the restyled
   files, so it wants its own pass when nothing else is in flight.
7. **Rewrite the habits-screen tests** on `createAndroidComposeRule`.
8. **Then** `buildFeatures.viewBinding = false`, which is the finish line the
   whole restyle has been walking towards.

### Done since this list was written

Kept because several entries record *why* something was built the way it was,
and because two of them contradict what this file used to recommend.

- **The `Week`/`Month` spinners are gone.** `TabsMMD` was the stated target and
  it does not fit: five spans, a row that divides the width evenly, and 16dp
  off each side of a tab leaves about 40dp of label — enough for "Week", not
  "Quarter". They are `ModalBottomSheetMMD` sheets of `RadioButtonMMD` rows
  instead, following the other rule (*a choice is a sheet*), which is what the
  frequency picker and the first-weekday setting already do.
- **All three View dialogs became MMD**, and the history editor was then
  **deleted** — it turned out to be unreachable (the Calendar tab going Compose
  had orphaned the card carrying its entry point), and the owner did not want
  it back. Days older than the three columns on the habits list can no longer
  be edited anywhere. That is a decision, not an oversight.
- **Both bundled pickers are deleted** — `com.android.datetimepicker` (26
  files) and `com.android.colorpicker`, with `pickers.xml` and every layout,
  colour and drawable they owned. That file held the last literal greys in the
  tree.
- **The six home-screen widgets are gone**, and with them a daily
  `setExactAndAllowWhileIdle` alarm that woke the CPU out of Doze forever to
  redraw widgets that could not exist.
- **The intro is gone.** Three slides explaining what a habit tracker is, to
  someone who had just installed one.
- **About is gone**, its version line moved to the foot of Settings.
- **Export has a home and no longer uses the share sheet** — it opens the file
  manager. The pending path lives in SharedPreferences because this device
  kills the app's process while the picker is open; see that commit.
- **Settings' second pass** restored midnight delay, sticky notifications and
  first-weekday. Reverse-days was restored and then **removed**: nothing had
  read it since the list went Compose, so the toggle did nothing. Reminder
  sound needs no row — "Customize notification" opens the system channel, where
  Android has kept sound since 8.0. The developer section is not coming back;
  developer mode is deleted.
- **ShowHabit's metrics were redesigned from mockups** rather than by taste.
  Streaks is a divided list, Frequency is a grid of squares in the calendar's
  own vocabulary and now lives under Score, and the score chart lost a
  duplicated heading, gained a control row and a y-axis gutter, and labels its
  months with single letters. See the three commits for what each fixed.

### Two things the audits flagged that are decisions, not bugs

- **The sort/filter `DropdownMenuMMD` on the habits list is an overflow menu**,
  which `eink-design.md` says MuditaOS K does not have anywhere. It is now the
  *only* one left — ShowHabit's went with developer mode — so either the rule
  needs an explicit exception written into it, or the sort options need to
  move. It is not a misuse of the component — its border, colours and zero
  elevation are all correct — it is a conflict with our own stated rule.
- **Row dividers are solid, not dotted.** `eink-design.md` asks for dotted.
  MMD cannot draw it (see MMD's own gaps). Either hand-roll a dashed `Canvas`
  line or amend the rule.

### The charts problem, mostly solved

It used to be framed as one large porting job. Most of it turned out not to
need porting at all: `StreakChart`, `FrequencyChart` and `TargetChart` were
**replaced** rather than moved, because each was drawing something a 1-bit
panel cannot show. Their states were already plain data, so the Compose
versions read the same `…CardState` the Views did and no core change was
needed.

What is left is the two that really are charts:

- **`ScoreChart`** — a scrolling line chart, and **`BarChart`** behind
  `show_habit_bar.xml`. Both are `android.view.View` with their own `onDraw`,
  and both are *interactive*: you drag them to scroll back through time, and
  that paging lives in `ScrollableChart`. Porting them means reimplementing
  that in Compose, which is where a regression would actually cost something —
  losing the ability to look back through your own history.
- `RingView` is still in the tree and no longer drawn by anything on this
  screen.

MMD has nothing chart-shaped, so neither becomes an MMD component. Pipeline A
(`BarChart`, `HistoryChart`, `Ring`, `CheckmarkButton`, `HabitListHeader` drawing
against the abstract `org.isoron.platform.gui.Canvas`) still moves with no core
changes when the time comes: Compose hands you the same
`android.graphics.Canvas` via `drawIntoCanvas { it.nativeCanvas }`.

## Things that are mine, not Mudita's

Flagged so a future pass can judge them rather than assume they came from MMD:

- **Halftone dithering**, `org.isoron.platform.gui.Halftone`. Non-pure colours
  become 4×4 Bayer dot patterns. Used by `AndroidCanvas.setColor` and by what
  is left of the View-drawn charts — `ScoreChart` and `BarChart`. It had five
  users; three of them (`StreakChart`, `FrequencyChart`, `TargetChart`) are
  deleted, and each was deleted **because** of what the dither did to it: a
  streak bar became a grey blob carrying a grey numeral, a frequency bubble
  became a smudge, a target bar put its own label on the boundary between
  filled and unfilled.

  That is worth stating plainly, because it qualifies the rule rather than
  overturning it. Halftone earns its place where a chart genuinely needs a
  midtone it can spare — gridlines, a ring's inactive arc. It is the wrong
  answer wherever a *shape* or a *number* has to stay legible, and there the
  MMD language (filled versus outlined, inversion for emphasis) wins. Text has
  always been exempt; the lesson is that small marks should be too.
  `Halftone.DOT` is **1** by preference; see "Known-good details".
- **The unknown-checkmark glyph** is a hollow cross: the same `fa_times` a
  recorded no draws, at the same size, outlined instead of filled. It was a
  hollow circle, which needed its own smaller size to stop out-weighing the
  check beside it and put a third shape in a row that holds two ideas. Sharing
  the cross's shape leaves fill as the only difference between a missed day and
  an unrecorded one, which is the distinction MMD draws everywhere else. The
  outline comes from drawing a solid glyph with `drawStyle = Stroke`; MMD has
  no icon for "no data".
- **`SelectionTopBar`** and **`ShowHabitTopBarMMD`** are compositions of
  `TopAppBarMMD`, not MMD components in their own right. MMD has no
  selection-mode bar because MuditaOS has no `ActionMode`.
- The `eink_rule` hairline, `selected_box_eink`, `toolbar_eink_background` —
  hold-overs keeping the remaining XML screens consistent. They die with the
  last XML screen. `card_eink_background` is no longer used by ShowHabit, which
  separates its blocks with `HorizontalDividerMMD` instead.

## Working notes

Build, install, screenshot:

```bash
cd C:\Users\Antonio\loop-habit-tracker-fork && .\gradlew :uhabits-android:assembleDebug
```

```bash
C:\Users\Antonio\android-sdk\platform-tools\adb.exe -s MK20250408317 install -r uhabits-android\build\outputs\apk\debug\uhabits-android-debug.apk
```

```bash
C:\Users\Antonio\android-sdk\platform-tools\adb.exe -s MK20250408317 exec-out screencap -p > shot.png
```

- **Force-stop before screenshotting**, or resource changes will not have
  reloaded and you will review a stale screen.
- **The panel repaints slowly, so a screenshot taken right after an `am start`
  or an `input tap` shows the previous screen — or a blank one.** This wastes
  an astonishing amount of time and looks exactly like a bug you just
  introduced: a blank body, a missing title, a screen that "did not change".
  Confirm what is actually there with
  `adb shell uiautomator dump /data/local/tmp/w.xml` and read the bounds and
  `text=` attributes, which are correct immediately, then screenshot for looks.
  `LazyColumnMMD` compounds this — it holds itself at `alpha = 0` until its
  items have been counted.
- **Do not chain `input tap` calls.** A tap that lands before the next screen
  is up hits whatever is underneath. One of them toggled a real habit's
  checkmark on a real habit while aiming at a button on the screen behind.
- **Verify a FontAwesome codepoint before using it.** The bundled
  `fontawesome-webfont.ttf` is a 1001-glyph subset and most of
  `fontawesome.xml` is commented out for that reason. A missing glyph renders
  as nothing, silently — an action button just disappears.
  `python -c "from fontTools.ttLib import TTFont; print(0xf1f8 in TTFont('uhabits-android/src/main/assets/fontawesome-webfont.ttf').getBestCmap())"`
- **MMD source: `git clone --depth 1 https://github.com/mudita/MMD.git`.**
  Read the component before using it; see rule 3 at the top. Also useful:
  `mudita/mmd-migrator`, `MuditaOS-K-Calculator-opensource`,
  `-Chess-opensource`. `mudita/MuditaOS` is the *older Pure* device — different
  generation, its `Radius = 0` contradicts the Kompakt's rounded buttons; treat
  as historical.
- **Do not put `--` inside an XML comment.** aapt2 rejects it, and it has
  broken the build three times.
- `core.autocrlf = true` on this machine makes two `uhabits-core` CSV export
  tests fail on line endings. **Pre-existing, not a regression** — expect
  273/275 from `.\gradlew :uhabits-core:jvmTest`.
- **`ktlintCheck` is red, and has been for the whole restyle**: 287 violations
  in `uhabits-android`, almost all "unnecessary trailing comma before )" plus a
  few import-order and unused-import hits. The restyle was written in a
  trailing-comma style this project's ktlint config rejects. It is mechanical —
  `ktlintFormat` fixes nearly all of it — but it rewrites most of the touched
  files, so it wants to be its own pass, done at a moment when nothing else is
  in flight. `uhabits-core` is clean.

## Repo

Fresh clone of `nebulion/uhabits`. The work lives on branch **`mmd-eink`**,
branched from `dev`, with 27 commits on it and **nothing pushed** — the remote
has never seen any of this.

**There is history now**, which there was not for most of this project's life:
the first of those commits captured 229 uncommitted paths that had no history
behind them at all. Everything since is committed as it lands. `git branch -f
dev mmd-eink` moves `dev` onto it if the branch is not wanted.

Two backup folders were made before the early destructive passes, both under
`C:\Users\Antonio\Backups\`:

- `uhabits-deleted-views-2026-08-17` - the deleted habits-list View classes,
  `ListHabitsRootView`, `ListHabitsSelectionMenu`, the whole Espresso suite,
  and the pre-fix (white-on-white) intro icons.
- `uhabits-reset-2026-08-17` - the original colour intro icons, an old
  `uhabits.db`, `local.properties`, unrelated Dolomites trail work that had
  been living in the repo root, and the previous e-ink attempt as a patch.

**Security:** the *old* clone's git remote had a GitHub personal access token
embedded in the URL, and it is also in the old `.claude/settings.local.json`
in that backup folder. The current clone uses a clean URL, but **that token
should be revoked.**
