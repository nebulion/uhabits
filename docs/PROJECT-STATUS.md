# Project status — Loop Habit Tracker on Mudita Mindful Design

Handoff notes. Read [eink-design-habits.md](../../eink-design-habits.md) first
for the design rules and their sources (the e-ink docs moved to the folder above
this repo on 2026-10-07, beside [MMD-GUIDE.md](../../MMD-GUIDE.md)); this file
covers what is done, what is half-done, and what is next.

> **2026-10-07: the review pass.** [PROJECT-REVIEWS.md](../../PROJECT-REVIEWS.md)
> found this app, the oldest port, behind the rules the owner set later in the
> other four. All of its code items are done, built from the kit the other apps
> already use (now in `org.isoron.uhabits.mmd`):
>
> - **System font, no Lato.** `MmdTheme` wraps `ThemeMMD` with MMD's type scale
>   in `FontFamily.Default`; `res/font/` is deleted, the XML theme and
>   `AndroidCanvas.setFont` use the platform face. Every screen uses `MmdTheme`.
> - **Dotted row dividers.** `DashedDividerMMD` (1px, 2dp dots, pixel-snapped):
>   habits list, Settings, streaks, sheets, the sort menu. Structural rules stay
>   the 3dp solid `HorizontalDividerMMD`.
> - **Popups are bottom panels** (pattern P5, zeroheight Dialog): full width at
>   the bottom, 3dp rule, centred Black heading, buttons stacked full width with
>   the solid one on top. Checkmark, Number, Confirm-delete and Habit type.
> - **No `LazyColumnMMD` anywhere.** `PagedList` (from Fit): one paint, a page
>   turn brings the last visible row to the top, back returns to the exact page,
>   thumb sized by height, scrollbar only where a list overflows. The habits list
>   always keeps the scrollbar's column (`canGrow`), so the conditional
>   `SCROLLBAR_GUTTER` arithmetic that shifted the day columns is gone.
> - **The flash is on by default**, every 12 lifted fingers in any activity
>   (`EinkRefresh` from Fit/Macros), with an **E Ink** settings page for on/off and
>   the interval.
> - **Hold and let go selects; hold and drag moves** (P7). No more "select, then
>   hold again". The list no longer scrolls on release.
> - **Score and Bar charts are Compose**, stepped with ‹ › (`HabitChartsMMD.kt`).
>   `ScoreChart`, `ScrollableChart`, the unused `FrequencyChart`, `StreakChart`,
>   `RingView`, `AndroidDataView`, both card Views and the three ShowHabit layouts
>   are deleted. ShowHabit is Compose only.
> - **Tap a calendar day to change it**, with ‹ › through history twelve weeks at
>   a time. Days older than the list's three columns can be edited again.
> - **Settings is grouped pages** (Interface, E Ink, Reminder, Sync, Data), each
>   row carrying its value under its title.
> - **A data copy on background**: `uhabits.db` in the app's external files
>   folder, pullable from a release build (`DataCopy`).
> - **No sync button.** Sync runs by itself: after every change, every 30s while
>   the app is open, and when it leaves the screen (`AutoSync`; see `sync.md`).
> - **Sort/filter keeps its dropdown**; the "no overflow menu" rule now says why
>   (see `eink-design-habits.md`).
>
> **Not done, because they are the owner's:** the device checks below (now
> including all of the above); whether to keep Automation (the last user of
> `viewBinding` and of the two remaining layouts); the launcher icon's stroke;
> pushing `mmd-eink`/`sync-desktop` to the fork as an off-machine backup;
> revoking the old token at the end of this file. Not done yet, and large:
> one `ktlintFormat` pass and Compose UI tests.

> **There is a second workstream now.** Two-way habit sync over Syncthing, plus
> a window and a terminal client in a new `:uhabits-desktop` module. It is
> working end to end on real hardware, both directions, deletions included, and
> it has its own handoff: **[sync.md](sync.md)** — read that for anything about
> sync, the desktop clients, or working on `uhabits-core` off Android.
>
> It is independent of the restyle: it adds no MMD component and changes no
> layout. Three things it did touch here, all recorded in that document —
> a Settings section, `SettingsRow.Action` gaining an optional `value` line, and
> a fix for `REQUEST_SETTINGS` and `REQUEST_CREATE_DOCUMENT` both being `107`,
> which had silently disabled Export, Import and Repair from Settings.
>
> One correction that belongs here rather than there: **`Log.i` does reach
> logcat on this device.** `Log.d` and `Log.e` do not, which is what the
> "Working notes" section below says and is what made the whole of logging look
> dead. `Log.i` is how the startup timings in "Install the release build" were
> measured.

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

**MMD ships no dotted divider.** `eink-design-habits.md` calls for a dotted
hairline between list rows. `HorizontalDividerMMD` can only draw solid, and the
`DashedDivider()` that `MenuMMD.kt`'s own KDoc calls **is not defined anywhere
in the library** — it is a dead reference in Mudita's docs. **Fixed** the way the
Anki kit did: `org.isoron.uhabits.mmd.DashedDividerMMD`, a 1px line of 2dp dots
snapped to whole device pixels.

**MMD has no dialog, and no dense text field.** There is no alert/confirm
component at all, and `TextFieldMMD` has one shape: `MinWidth` 280dp,
`MinHeight` 56dp, 16dp padding on all four sides. There is no compact variant
to put inline inside a sentence.

## Where things stand

| | |
| --- | --- |
| Layout files | **2** (from 45) — target 0 |
| viewBinding | still `true`, kept alive only by Automation (`AutomationBinding`) |
| Screens on MMD | Settings, Habits list, EditHabit, ShowHabit (all Compose now), Snooze |
| Popups on MMD | Checkmark, Number, Confirm-delete, HabitType (bottom panels); Frequency, Reminder, Weekdays (sheets); Snackbar |
| Still on Views | Automation only |
| The 2 layouts | `automation.xml` + `toolbar.xml` (the Tasker plugin, and a toolbar nothing else inflates) |

### Done

**Foundation.** Compose enabled, `com.mudita:MMD:1.0.2` resolving. Opt-in for
`ExperimentalMaterial3Api` is a compiler arg in
`uhabits-android/build.gradle.kts`, not per-class — annotating classes leaked
the annotation into unrelated callers.

**Design tokens, all traced to MMD source.** The type scale in `dimens.xml`
re-derived from `TypographyMMD.kt` (nothing below 14sp), set in **the phone's
system font**, not the Lato MMD bundles (`MmdTheme`, 2026-10-07; Lato was copied
in at first and is now deleted); `eInkColorScheme` is literally black and white
for *every* token, which is where the zero-grey rule comes from.

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
container is ours, built to zeroheight's Dialog and the Kompakt's Notes delete
prompt (P5): **anchored to the bottom at full width**, a 3dp black rule and
2dp of white across its top, a centred Black 25sp heading, buttons stacked
full width at 56dp with the solid one on top, no border, no elevation, no dim,
no window animation. (Until 2026-10-07 it was a bordered box in the middle of
the screen, which is how no Mudita app draws a dialog.) Having exactly one of
it is the point — the checkmark editor, the number editor and the delete
confirmation are the same object with different contents.

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
over the window for 100ms, which is InkOS's exact mechanism and duration. Since
2026-10-07 it is the Fit/Macros version: it counts **every lifted finger in
every activity** (a window callback installed from `HabitsApplication`), and it
is **on by default, every 12**, behind **Settings -> E Ink**, where the interval
can be 6, 8, 12, 16 or 24. It used to count commands and pages scrolled on the
habits list only, was off by default, and was never once seen to fire. See
"Clearing ghosting" for why it is a heuristic and why the `meink` service was
not used.

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

**Settings is grouped drill-down pages** (2026-10-07, UI-PATTERNS A4), like the
Kompakt's own Settings and the owner's Anki and Tasks: the first page lists
Interface, E Ink, Reminder, Sync and Data, with what each holds under its name,
and the version as a line of text. Rows are MMD list rows -- Black 21sp label
when a value sits under it, Medium 18sp value, 64dp, dotted dividers from the
label -- and a toggle row is one target with an inert `SwitchMMD`. The open page
is saved, so a recreate keeps it.

**The stores cannot replace this build.** See below.

### Half-done — read before touching the habits screen

- **Drag-to-reorder: built, rebuilt, and awaiting one human check.**
  **Hold a habit's name and let go to select it; hold and drag, without
  letting go, to move it** (MMD-GUIDE P7, the same in all the owner's apps;
  until 2026-10-07 it was "long press to select, long press again to pick up").
  The list holds still while you drag and the separator you would drop into
  thickens; the reorder happens once, on release. Holding at the top or bottom
  edge turns a page every second.

  **The gesture is owned by a container around the list, not by a row**, and
  four separate bugs are the reason. Anyone touching this should read them
  before "simplifying" it back:

  1. `positionChange()` reports a movement as **zero** once the change has been
     consumed. Consuming before reading silently zeroed every drag: the habit
     picked up and then never travelled. Read first, consume second — or use
     `positionChangeIgnoreConsumed`.
  2. The list claims swipes for its own paging (`PagedList.pageOnSwipe`, as
     `LazyColumnMMD` did). `verticalDrag` and
     `detectDragGesturesAfterLongPress` both treat a claimed movement as a
     *cancelled* drag and give up, so neither works here. The gesture runs in
     the **`Initial` pointer pass**, where a parent sees events before its
     children, and consumes only once a hold is recognised — so taps, swipes
     and the day cells' own long press are untouched.
  3. Reading the finger as `thatRow.offset + local y` broke the moment a page
     turned: the row left `visibleItemsInfo`, the lookup returned nothing, and
     no boundary could be computed again — you could scroll but not place.
     Worse, a `LazyColumn` may **dispose** a row it has scrolled past,
     destroying the node hosting the gesture. A container is never disposed and
     its coordinates are the list's own.
  4. Folding tap, long press and drag into a single detector *on the row* —
     tried, reverted. It stopped the name column receiving any pointer events
     at all, taps included. So the name keeps a plain `clickable` for the tap,
     and the hold (select or drag) belongs to the container alone; the name
     must not have a long click of its own.

  Still open:

  - ~~**The list scrolls when you release.**~~ **Fixed 2026-10-07.** It was
    `LazyColumn` re-anchoring on the keyed first-visible item, which a drop
    moves. The position is snapshotted before the drop and put back with
    `requestScrollToItem` in the same frame the reordered list arrives.
  - **It only works in manual order** (`BY_POSITION`), silently. Sorted by name
    or score the habit picks up and the drop does nothing, with no explanation
    anywhere.
  - **The page-turn case has never been confirmed working by a human.** It
    cannot be driven over adb at all — see "Verified on the device".

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
- **The scrollbar's column is 40dp (`PagedListDefaults.ScrollbarGutter`), and
  on the habits list it is always there.** 24dp of arrow inside 8dp of padding
  either side, beside the list rather than inside it. `PagedList` drops it on a
  list that fits, which would make the day heading (outside the list) need a
  conditional inset; the habits list passes `canGrow = true` instead, so the
  heading takes the constant and the columns never move.
- **Lists are `PagedList`, never `LazyColumnMMD`** (`org.isoron.uhabits.mmd`).
  `LazyColumnMMD` drew its rows at alpha 0 for the first frame (every screen
  painted twice), paged a fixed number of *items* and sized its thumb by item
  count. `PagedList` is Foundation's `LazyColumn` with scrolling off and its
  own swipe-to-page: the last visible row becomes the first, back returns to
  the exact page, the thumb is sized by height. Emit one item per row; a row
  taller than the page is paged through by the page.
- **3 day columns, sized by weight.** 140dp name column, then the remaining
  ~180dp of the 320dp content area split three ways. Four columns fit
  arithmetically but leave too little for a name, and the headings start to
  wrap.
- **Disabled pager arrows use `mmd_chevron_dotted_*`**, not a grey solid one.
- `threshold` is `targetValue / frequency.denominator`, not `targetValue`.
- `isShortToggleEnabled` swaps *which gesture* toggles; it does not change the
  cycle. Numerical habits have no toggle path at all.

## Install the release build, not the debug one

**Cold start on the Kompakt: 3.9s debug, 0.87s release.** Same code, same
device, four times faster, and none of it algorithmic — `debuggable=true`
disables ART optimisations and the Compose composition of this screen is where
the time goes. Everything else measured on the way to that number was small by
comparison: sync 5.2s → 140ms, the habit cache 24ms, the score recompute moved
off the startup path.

```bash
cd C:\Users\Antonio\mmd\habits-mmd && .\gradlew :uhabits-android:assembleRelease
```

```bash
C:\Users\Antonio\android-sdk\platform-tools\adb.exe -s MK20250408317 install -r uhabits-android\build\outputs\apk\release\uhabits-android-release.apk
```

It is signed with the **Android debug keystore**, deliberately -- see the next
section; that is what keeps the stores out, and it means release and debug
builds install over each other without losing data.

**The debug build is still what to install while working on the app**, because
`run-as` needs `debuggable=true` and every diagnostic in this document goes
through it: reading the database, reading `files/sync-status.txt`, editing
preferences. A release build can do none of that.

## Startup, measured (2026-09-24)

Release build, owner's 44 habits, `am start -W` after `am force-stop`, and
"Fully drawn" from `reportFullyDrawn` once the habits themselves are on screen:

| | first frame | habits visible |
| --- | --- | --- |
| debug build (what was installed) | 4,350-4,630ms | - |
| release, plain `adb install` | ~1,000ms | - |
| + installed with its `.dm` (profile compiled at install) | ~680ms | ~835ms |
| + R8 optimising libraries (APK 15.6 -> 9.5MB) | ~615ms | - |
| + an app baseline profile | ~548ms | ~600ms |
| + fonts preloaded, reminders and sync off the startup path | **~465-505ms** | **~545-590ms** |
| + no loading state: held until complete, one paint | **~600ms** (same frame) | **~600ms** |
| + no starting window (`windowDisablePreview`) | **~575ms** (same frame) | **~575ms** |
| relaunch while the process is alive (warm) | **~205-245ms** | |

**No starting window.** Mudita's Meditation cold-starts in ~510ms and still
feels instant, because Android draws no starting window for it: the launcher
stays on the panel until the app's first real frame replaces it, one E Ink
refresh. This app used to get a blank white splash in between, two full-screen
refreshes, and that is what read as slow. `ListHabitsLaunchTheme` sets
`windowDisablePreview`, so the launcher now gives way directly to the finished
list; the held first draw in `ListHabitsActivity` makes that one paint. A
system trace of a launch should show no `addStartingWindow` and no
`Splash Screen` layer.

For scale, Mudita's own Calculator and Meditation (preinstalled, fully AOT)
cold-start in 510-560ms on the same device.

**Install with the `.dm`, or the profile is not used.** A plain `adb install`
leaves the app `status=verify` -- nothing compiled -- and this device's
background dexopt is the only other thing that would compile it. AGP writes the
dex metadata next to the APK:

```bash
adb -s MK20250408317 install-multiple -r uhabits-android/build/outputs/apk/release/uhabits-android-release.apk uhabits-android/build/outputs/apk/release/baselineProfiles/0/uhabits-android-release.dm
```

`adb shell dumpsys package dexopt | grep -A2 "org.isoron.uhabits]"` should then
say `speed-profile` / `install-dm`.

**"Don't keep activities" is on on this device** (`always_finish_activities=1`).
Every HOME destroys the screen, so a relaunch is a 220ms warm start instead of
a hot one. It is a developer option, not something the app can change.

**Regenerating `src/main/baseline-prof.txt`.** This device waits 150 seconds
before ART first saves a runtime profile (`dalvik.vm.ps-min-first-save-ms`), so
short runs record nothing. Run the release build for three minutes, then
`adb shell cmd package dump-profiles org.isoron.uhabits` and pull
`/data/misc/profman/org.isoron.uhabits-primary.prof.txt`. That dump is in ART's
Java-signature format and has to be converted to descriptor rules.

**Measuring.** The release build is `profileable`, and startup is wrapped in
`Trace` sections (`traced { }`, and every task-runner job by class name), so
`adb shell atrace -a org.isoron.uhabits am wm view gfx dalvik sched` during a
launch shows the whole of it, app code included. `simpleperf record --app` works
too.

**One paint, on purpose.** The first draw is held (`holdSplashUntilDrawn`) until
the habits are loaded, the list has laid out its rows, and the frame after that
has composed `LazyColumnMMD`'s scrollbar -- it only adds the scrollbar once it
knows the list can scroll, so releasing on "loaded" painted it a frame late.
Header, dates, rows and scrollbar reach the panel together. It costs ~30ms of
"habits visible" against drawing the header first with a loading state, which
is the trade the owner asked for. The load now starts in `onResume`.

**Where the ~465ms first frame of the two-paint version went:** ~60ms of system before the process exists,
~57ms process start, 72ms `bindApplication` (27ms of it loading the app image),
51ms creating the activity, 29ms resuming it, then the first frame: ~180ms on
the main thread (Compose's first composition and measure ~75ms, drawing ~35ms)
and ~45ms on the render thread, 16ms of which is creating the GL context. The
habits arrive in a second frame of ~70ms.

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
2. **The signature already blocks it.** Both builds are signed with the Android
   debug key; every store's APK is signed with a different one, so Android
   rejects the install outright rather than overwriting the app. This defence
   is free and was already in place -- but it fails silently as a *failed
   install*, not as "no update offered", which is why defence 1 is worth having.

   **Switching to the release build did not weaken this**, which is the obvious
   worry, so it was checked rather than assumed. `release` takes
   `signingConfigs["release"]` when `LOOP_KEY_ALIAS` is set in the environment
   and the debug keystore otherwise, and with no key set the release APK's
   certificate is byte-identical to the debug one:

   ```
   release APK      f2243f9087a120c9cb563890692cb8a0b87951f66ea8466653ff2c02bf3e0d5e
   debug APK        f2243f9087a120c9cb563890692cb8a0b87951f66ea8466653ff2c02bf3e0d5e
   debug.keystore   F2:24:3F:90:87:A1:20:C9:CB:56:38:90:69:2C:B8:A0:B8:79:51:F6:6E:A8:46:66:53:FF:2C:02:BF:3E:0D:5E
   ```

   `versionCode` is still 1000000 on the installed release build, and the
   `applicationId` is unchanged.
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

1. **Try the 2026-10-07 review pass on the phone** (owner): the new hold
   gesture (hold-release selects, hold-drag moves, across a page turn — it
   cannot be driven over adb); the bottom panels; the system font; Settings'
   pages; ‹ › on both charts and the calendar, and tapping a calendar day;
   that the flash fires about every twelve taps; that `uhabits.db` appears in
   `/sdcard/Android/data/org.isoron.uhabits/files/` after leaving the app.
2. ~~See the ghosting flash fire.~~ Now on by default; see 1.
3. ~~The two remaining charts.~~ **Done 2026-10-07**: Compose, stepped with ‹ ›.
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
   whole restyle has been walking towards. Since the charts went Compose,
   Automation (4) is the only thing left in the way.
9. **Sort by name or score silently disables dragging.** The habit picks up
   and the drop does nothing. Either hide the drop line outside manual order or
   say so.

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

### Two things the audits flagged, now decided (2026-10-07)

- **The sort/filter `DropdownMenuMMD` stays, and the rule says why.** MMD
  Menus is a real component (zeroheight 975be6: an in-screen menu opened from
  a header icon, pattern P3, the Chess game menu), and the owner's other four
  apps have since adopted one. What MuditaOS K does not have is the *Android
  overflow* (⋮ hiding the screen's main actions). This menu holds six items
  (MMD's maximum), its groups split by a dotted line.
- **Row dividers are dotted.** `DashedDividerMMD`; see MMD's own gaps.

### The charts problem, solved (2026-10-07)

`ScoreChart` and `BarChart` are `ScoreChartMMD` and `BarChartMMD` in
`HabitChartsMMD.kt`: drawn in Compose from the same `ScoreCardState` and
`BarCardState`, a page of buckets at a time with ‹ › and the range between the
arrows, dotted gridlines where the Views drew halftoned grey. Nothing below is
current any more; it is kept for why.

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
  become 4×4 Bayer dot patterns. Used by `AndroidCanvas.setColor`; every
  View-drawn chart that also used it is deleted (the last two, `ScoreChart` and
  the hosted `BarChart`, went Compose on 2026-10-07 with dotted gridlines
  instead). Three of them were deleted **because** of what the dither did: a

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
cd C:\Users\Antonio\mmd\habits-mmd && .\gradlew :uhabits-android:assembleDebug
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
