# Sync, and the desktop clients

Two-way habit sync between the Kompakt and a computer, carried by **Syncthing**,
plus two clients for the computer end: a window and a terminal UI.

This is the second workstream in this repo. The first is the MMD restyle, and it
has its own handoff in [PROJECT-STATUS.md](PROJECT-STATUS.md); read that one for
anything about the phone's screens. The two barely touch — sync adds no MMD
component and changes no layout — with two exceptions, both recorded below.

**The phone's database does not move.** `uhabits.db` stays exactly where it
always was, on the same schema (version 25), read and written on every tap as
before. The shared folder is a mirror. With no folder configured, none of this
runs.

## Where things stand

| | |
| --- | --- |
| Sync | working end to end on real hardware, both directions, deletions included |
| Desktop database | `%APPDATA%\uhabits\uhabits.db`, holding the owner's 42 habits |
| Clients | `uhabits gui` (Swing, gruvbox dark) and `uhabits` (JLine terminal UI) |
| Tests | 13 engine + 4 date in core, 10 round-trip + 3 store on the desktop |
| Branch | `sync-desktop`, branched from `mmd-eink`, nothing pushed |

Merge it with `git checkout mmd-eink && git merge sync-desktop`.

## Setting it up

### On the phone

**Settings → Sync**

1. **Allow access to shared storage** — only shown when it has not been granted.
   Opens the system toggle. With `targetSdk 36` an app cannot otherwise touch
   `/sdcard`, which is where the Syncthing folder lives.
2. **Shared folder** — shows the folder in use under the title; tapping opens
   the picker. If the device has no document picker at all, it falls back to
   `/sdcard/Sync/loop`, which is where Syncthing already keeps its folder here.

Then **Sync now** and **Stop syncing** appear. Sync otherwise runs whenever the
app comes to the foreground, next to the automatic backup.

### On the computer

```bash
./gradlew :uhabits-desktop:installDist
```

That produces `uhabits-desktop/build/install/uhabits/bin/uhabits` (and a `.bat`
beside it). Put it on your PATH:

```bash
ln -s "$PWD/uhabits-desktop/build/install/uhabits/bin/uhabits" ~/.local/bin/uhabits
```

Point it at the same folder once — it is remembered afterwards:

```bash
uhabits --sync-folder ~/Sync/loop sync
```

From then on, bare `uhabits` opens the terminal client and `uhabits gui` opens
the window.

**On Windows, never use `setx PATH "%PATH%;..."`.** In `cmd`, `%PATH%` expands
to the system and user variables joined, and `setx` writes the whole thing back
into the *user* one — duplicating every system entry and pushing the value past
the 1024-character limit at which `setx` silently truncates. This was done here
once and had to be undone. The safe form touches only the user half:

```bash
powershell -c "[Environment]::SetEnvironmentVariable('Path', [Environment]::GetEnvironmentVariable('Path','User') + ';C:\some\folder', 'User')"
```

A PATH change reaches only *new* processes. An open terminal keeps the one it
was started with.

### Arch Linux

Works as-is. It needs a JRE 17 or newer (`jdk17-openjdk` or later);
`sqlite-jdbc` ships the `linux-x86_64` native and JLine drives any terminal.

One caveat: `sqlite-jdbc` unpacks its native library into `/tmp`. If `/tmp` is
mounted `noexec`, point it somewhere else:

```bash
JAVA_OPTS=-Dorg.sqlite.tmpdir=$HOME/.cache/uhabits uhabits
```

Paths follow XDG: the database is `$XDG_DATA_HOME/uhabits/uhabits.db` (falling
back to `~/.local/share`), settings are in `$XDG_CONFIG_HOME/uhabits/`. On
Windows both live under `%APPDATA%\uhabits`. `uhabits where` prints them, and
says how many habits are actually in the folder it is pointed at.

## The commands

| | |
| --- | --- |
| `uhabits gui` | the window |
| `uhabits` | the terminal client |
| `uhabits list [--days N]` | the grid, printed once |
| `uhabits show <habit>` | one habit, with streaks |
| `uhabits check <habit> [date] [yes\|no\|skip\|clear\|<number>]` | record a day |
| `uhabits add <name> [--question Q] [--numerical --unit U --target N]` | create a habit |
| `uhabits delete <name> --yes` | delete a habit **and its history, everywhere** |
| `uhabits sync` | reconcile with the shared folder |
| `uhabits where` | the paths in use, and whether the folder has anything in it |
| `uhabits dump [rows cols]` | print one frame of the terminal client, for debugging |

`--db`, `--sync-folder` and `--config` override the paths. A leading `~` is
expanded by the program, because PowerShell does not expand it for a native
executable — `--sync-folder ~/Sync/loop` once created a directory literally
named `~`, and sync then ran against it and truthfully reported nothing to do.

`delete` takes an exact name, unlike every other command, which fall back to a
substring match. Deleting the wrong habit takes its whole history with it.

### The marks

```
●  done                      ○  credited by frequency
×  missed                    –  skipped
·  nothing recorded          42 a numerical habit's value
```

One shape family, and shape carries the whole meaning — the grid reads with no
colour at all. `○` is deliberately not a tick: a non-daily habit credits the
days around one you did perform, and drawing those identically hides what you
actually did.

### The window

`uhabits gui`. Habits down the side, days across, click a cell to cycle it.
Toolbar: ◀ / Today / ▶, Sync now, Add habit, Delete habit. Status line at the
bottom reports what each action did. It syncs when it opens, the way the phone
does when it comes to the foreground.

**Swing, not Compose Desktop or JavaFX**, and the reason is dependencies: Swing
is part of the JDK, so the window adds nothing to resolve, no Gradle plugin to
pin against the Kotlin version, and nothing that can fail to download. Compose
Multiplatform would extend more pleasantly and could eventually share
composables with the Android side, but it needs a plugin matched to Kotlin
2.1.10 and the cost of getting that wrong is a client that will not build. What
the screen is — a table of habits by days — is the exact shape `JTable` exists
for, so the whole window is one table model and one cell renderer.

Gruvbox dark, from the same hex values the terminal uses (`GruvboxPalette`), so
the two cannot drift. Two details worth keeping: `show()` blocks until the
window closes, because Swing builds on its own thread and `main` would otherwise
return and close the database underneath it; and sync runs on a `SwingWorker`,
because it touches dozens of files and doing that on the event thread freezes
the window. The **cross-platform** look and feel is used rather than the system
one — Windows and GTK draw many components natively and ignore colour keys,
which yields a dark table with white buttons.

### The terminal client

`uhabits`. `j`/`k` habit, `h`/`l` day, `space` toggle (numeric habits prompt for
a value), `Enter` detail, `t` today, `s` sync, `q` quit.

**JLine**, chosen over Lanterna because Linux had to be first-class without that
being a Windows compromise: Lanterna substitutes a **Swing window** on any
machine with a graphical environment, and its native `WindowsTerminal` is
documented as only partially functional. JLine drives the real terminal on both,
is what Gradle, Maven and the Kotlin REPL use, and ships working `nano`, `less`
and `tmux` built on the same `Display` / `KeyMap` / `BindingReader` primitives.

Colours are 256-colour indices rather than 24-bit: truecolour escapes are
flattened to the nearest ANSI 8 by a surprising number of terminals and most
`TERM=screen` setups, which would turn gruvbox into primary colours.

On a terminal JLine reports as "dumb" — a plain conhost window, or output
redirected to a file — it prints the list instead of drawing a broken
full-screen UI.

## How it works, and why

**One folder per habit**, holding `habit.json` and `entries.json`:

```
loop/
  dental__4e818585e6434d9bb1e556c2e0222472/
    habit.json
    entries.json
```

The slug is there so the folder can be read by a person; the UUID so it can be
found by the program after a rename. They are separated by a double underscore,
which a slug never contains, so the UUID is recoverable from the name without
opening any file — which matters on Android, where every lookup is a Storage
Access Framework round trip.

**Syncthing cannot merge file contents.** It replicates whole files, picks a
winner by modification time, and leaves the loser as a `.sync-conflict-*` copy —
there are five such `.kdbx` files in this very folder from another application.
So the only real design lever is how large a file it is handed. One file per
habit means a conflict costs one habit, and because two copies of one habit's
history are all but identical, the next sync unions them, reports any day the
two devices genuinely disagreed about, and deletes the conflict copy.

**The merge is three-way**, against a private cache of what the folder said at
the end of the previous sync (the *base*, in `files/sync-base` on the phone and
beside the database on the desktop). The base is what distinguishes an edit from
a non-edit. Without it, "local and folder disagree" has no answer — and
un-ticking anything would silently undo itself on the next round, which is the
first bug you would hit.

Deletion works because a habit missing from the folder *but present in the base*
was deleted elsewhere, whereas one missing from both is simply new here.

Changes are applied through the existing commands — `CreateHabitCommand`,
`EditHabitCommand`, `DeleteHabitsCommand`, then `habit.recompute()` — so the
write path is the one `LoopDBImporter` has always used.

**Only habits and entries sync.** Not preferences, reminders, widget state or
the E Ink settings — those are per-device by nature.

### Doing nothing, quickly

Almost every sync has nothing to do, and finding that out honestly means reading
and parsing every file on both sides and rebuilding the local snapshot entry by
entry. On the Kompakt that was **5.2 seconds on every launch**, competing for the
same locks the habits list needs to draw.

So a sync first compares a stamp: the shared folder's file sizes and
modification times, plus the database file's size and modification time and two
aggregate queries over it. If nothing on either side has moved, it stops there.
That is **140ms**, and a real change on either side still takes the full path.

The stamp errs towards "changed" on purpose — SQLite touches its file for
reasons that are not edits, and a spurious yes costs one ordinary sync rather
than a missed change. The aggregate queries are there because filesystem
timestamps are not always finer than a second, and two writes in one tick would
otherwise look like none.

Dates are converted arithmetically rather than through `SimpleDateFormat`, for
the same reason: that ran once per entry per sync, about five thousand times,
building a fresh formatter each call because the shared one is not thread-safe.
`SyncDateTest` checks the arithmetic against the formatter it replaced for every
day across a decade.

### Two safety rails

Both exist because the failure they prevent costs every habit you have.

- **An empty shared folder next to a non-empty base aborts the sync.** Read
  literally it means "everything was deleted elsewhere"; in practice it means
  Syncthing has not populated the folder yet, storage was not mounted, or the
  folder was cleared by hand.
- **The base records which folder it belongs to.** Repointing at a different
  folder discards the base and starts fresh, instead of reading the old folder's
  history as a list of deletions.

## Known limits

- **Same day, two different values, two devices, one sync window.** One cell
  resolves by a fixed precedence (`YES_MANUAL` > `SKIP` > `NO` > `UNKNOWN`) and
  is reported. Not silently swallowed, but not reversible either.
- **Read-modify-write races.** Bounded to one habit, and `.stversions` is
  enabled on this folder, so the overwritten copy is recoverable.
- **Timezones.** `Timestamp` is a day number; two machines in different
  timezones disagree about where the day boundary falls. Fine for one person.
- **Nothing detects a stalled Syncthing.** The clients show what they last did;
  they cannot tell you the transport is down.
- **Desktop sync is not automatic.** It runs on `uhabits sync`, on `s` in the
  terminal client, and when the window opens. The phone syncs by itself.

## Two bugs in uhabits-core, found from off-Android

Both had been there for years and neither can be hit from the app. **Anything
that runs core outside Android is walking on untested ground.**

- **`JdbcDatabase.update` stringified every bound value**, so a null wrote the
  literal text `"null"` into the column and read back as `0`. Android never saw
  it — it uses `AndroidDatabase` — and core's own tests never updated a nullable
  column. It corrupted `reminder_hour` the instant anything edited a habit
  off-device: every habit without a reminder came back carrying one, set to
  00:00 on no days. `insert` was always correct; only `update` pre-converted.
- **`MigrationHelper.migrateTo` never records the version it reached**, and no
  `.sql` migration writes `user_version` either. `SQLiteOpenHelper` stamps it on
  Android. Any non-Android client that migrates a file which outlives the
  process replays every migration on the next start and dies on "table Habits
  already exists". `HabitStore` stamps it.

Upstream almost certainly still has both.

## Traps worth knowing

- **`DocumentFile.createFile` does not overwrite.** Handed a name that exists it
  makes `name (1)`, then `name (2)`, reporting success every time. That is
  exactly how this device's DecSync folder ended up holding `tasks (1)` through
  `tasks (13)`. `AndroidSyncFolder` looks a file up first and refuses outright
  if the provider renames what it was given.
- **Open SAF output streams `"wt"`, not `"w"`.** The `t` truncates; without it a
  shorter document leaves the tail of the longer one it replaced, which turns
  valid JSON into trailing junk.
- **`takePersistableUriPermission` or the grant dies at reboot.** There was no
  such call anywhere in this app, which is why the half-built `publicBackupFolder`
  preference never worked.
- **`Log.i` reaches logcat on this device; `Log.d` and `Log.e` do not.** This
  contradicts what PROJECT-STATUS said for a long time, and cost a debugging
  session before it was noticed.

## When it does nothing

Each sync writes one line saying what it did:

```bash
adb -s MK20250408317 exec-out run-as org.isoron.uhabits cat files/sync-status.txt
```

```
2026-09-08 15:51:31  ok in 137ms: wrote=0 created=0 updated=0 deleted=1 aborted=false
```

`no usable folder` there means the preference is unset or shared-storage access
was never granted; the line prints both. **`run-as` needs a debug build** — the
release build cannot be inspected this way.

On the desktop, `uhabits list` prints the resolved database path, its size, how
many habits are in it and the JVM in use when it has nothing to show. That
distinguishes an empty database from a full one whose habits were filtered out,
from a path that resolved somewhere unexpected — all three of which have been
suspected in turn.

## Two things this touched outside sync

- **`REQUEST_SETTINGS` and `REQUEST_CREATE_DOCUMENT` were both 107.** Kotlin's
  `when` takes the first matching branch, so every result coming back from
  `SettingsActivity` was routed to the create-document handler — silently
  disabling Export DB, Export CSV, Import and Repair. Fixed while adding the
  sync rows, which would have been dead on arrival for the same reason.
- **`SettingsRow.Action` gained an optional `value`**, shown under the title.
  The shared-folder row uses it; choosing the folder and seeing which one is
  chosen were previously two separate rows asking one question.

## Verified

Against the owner's real database (42 habits, ~5,200 entries) on
`MK20250408317`, over the real Syncthing folder:

- phone publishes 84 files; a second run rewrites nothing;
- Syncthing carries the folder to the PC, and `uhabits sync` reconstructs all
  42 habits and 5,200 entries there;
- a habit created on the desktop reaches the phone (~12s through Syncthing);
- deleting it on the desktop deletes it on the phone, and it does not come back
  on the following round;
- the database passes `integrity_check` throughout.

A verified copy of the database as it stood before any of this is at
`C:\Users\Antonio\Backups\uhabits-presync-2026-09-06.db` — 43 habits at the
time, including the throwaway `zzz test`.

## A warning about verifying this from an agent

Claude Code's tools run in a sandbox, and **file writes there are not
necessarily visible to the real machine.** An entire debugging session went into
"the desktop database is empty" before that was the explanation: the sync that
populated it had run inside the sandbox. `adb` is unaffected — it talks to real
hardware — and so is anything Syncthing does.

If a file the agent wrote cannot be seen from a normal terminal, suspect this
before suspecting the code.
