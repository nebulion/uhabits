# Sync, and the desktop client

Two-way habit sync between the Kompakt and a computer, carried by **Syncthing**,
plus a terminal client for the computer end.

The phone's database does not move. `uhabits.db` stays exactly where it always
was, on the same schema, read and written on every tap as before. The shared
folder is a mirror. With no folder configured, none of this runs.

## Setting it up

### On the phone

**Settings → Sync**

1. **Allow access to shared storage.** Opens the system toggle. Needed because
   with `targetSdk 36` an app cannot otherwise touch `/sdcard`, which is where
   the Syncthing folder lives.
2. **Use /sdcard/Sync/loop.** Publishes every habit into that folder and starts
   syncing.

Sync then runs whenever the app comes to the foreground, next to the automatic
backup. **Sync now** forces a round.

There is also a **Shared folder** row that opens the file picker, for choosing
somewhere else. It needs no special permission, but it means navigating
DocumentsUI on an E Ink panel, so it is the second-choice route.

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

From then on, bare `uhabits` opens the terminal client.

### Arch Linux

Works as-is. It needs a JRE 17 or newer (`jdk17-openjdk` or later); `sqlite-jdbc`
ships the `linux-x86_64` native and JLine drives any terminal.

One caveat: `sqlite-jdbc` unpacks its native library into `/tmp`. If `/tmp` is
mounted `noexec`, point it somewhere else:

```bash
JAVA_OPTS=-Dorg.sqlite.tmpdir=$HOME/.cache/uhabits uhabits
```

Paths follow XDG: the database is `$XDG_DATA_HOME/uhabits/uhabits.db` (falling
back to `~/.local/share`), settings are in `$XDG_CONFIG_HOME/uhabits/`. On
Windows both live under `%APPDATA%\uhabits`. `uhabits where` prints them.

## The commands

| | |
| --- | --- |
| `uhabits` | the terminal client |
| `uhabits list [--days N]` | the grid, printed once |
| `uhabits show <habit>` | one habit, with streaks |
| `uhabits check <habit> [date] [yes\|no\|skip\|clear\|<number>]` | record a day |
| `uhabits add <name> [--question Q] [--numerical --unit U --target N]` | create a habit |
| `uhabits delete <name> --yes` | delete a habit **and its history, everywhere** |
| `uhabits sync` | reconcile with the shared folder |
| `uhabits where` | the paths in use |
| `uhabits dump [rows cols]` | print one frame of the client, for debugging |

In the client: `j`/`k` habit, `h`/`l` day, `space` toggle (numeric habits prompt
for a value), `Enter` detail, `t` today, `s` sync, `q` quit.

`✓` done · `+` credited by frequency · `✗` missed · `→` skipped · `·` nothing
recorded. The `+` is deliberately not a tick: a non-daily habit credits the days
around one you did perform, and showing those identically hides what you
actually did.

## How it works, and why

**One folder per habit**, holding `habit.json` and `entries.json`:

```
loop/
  dental__4e818585e6434d9bb1e556c2e0222472/
    habit.json
    entries.json
```

The slug is there so the folder can be read by a person; the UUID so it can be
found by the program after a rename.

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

**Only habits and entries sync.** Not preferences, reminders, widget state or
the E Ink settings — those are per-device by nature.

### Two safety rails

Both exist because the failure they prevent costs every habit you have.

- **An empty shared folder next to a non-empty base aborts the sync.** Read
  literally it means "everything was deleted elsewhere"; in practice it means
  Syncthing has not populated the folder yet, storage was not mounted, or the
  folder was cleared by hand.
- **The base records which folder it belongs to.** Repointing at a different
  folder discards the base and starts fresh, instead of reading the old folder's
  history as a list of deletions.

## When it does nothing

`Log.d` and `Log.e` do not reach `logcat` on this device — see
[PROJECT-STATUS.md](PROJECT-STATUS.md) — so each sync writes one line saying what
it did:

```bash
adb -s MK20250408317 exec-out run-as org.isoron.uhabits cat files/sync-status.txt
```

```
2026-09-08 15:51:31  ok: wrote=0 created=0 updated=0 deleted=1 aborted=false
```

`no usable folder` there means the preference is unset or shared-storage access
was never granted; the line prints both.

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
