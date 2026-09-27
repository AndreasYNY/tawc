# Terminal-first home screen

Opening the app lands you in a shell, the way termux does. The home
screen becomes one pane for the open distro — terminal or apps, with a
FAB toggling between them; intro with nothing installed; distro info
while the distro isn't usable. `TerminalActivity` and
`LauncherActivity` fold into `MainActivity`; the drawer and the
open-distro state from the previous pass stay.

Builds on the shipped one-open-distro home (notes/android.md "Home
screen"). Nothing here touches the compositor.

## Today

- `MainActivity`: drawer scaffold, distro label + state line + a search
  stub that opens `LauncherActivity`, Terminal FAB that opens
  `TerminalActivity`. ⋮ holds Distro info, Run command…, Task manager,
  Settings.
- `TerminalActivity`: its own document task per distro
  (`tawc://terminal/<id>`, `documentLaunchMode="intoExisting"`), no
  toolbar, `TerminalTabBar` + `TerminalView` + extra keys. Sessions live
  in `TerminalSessions` with a `Reason.Terminal` hold each, so any shell
  means a foreground service and a notification. Back backgrounds the
  task; swiping the card kills that distro's shells; the last shell
  exiting finishes the activity. Command tabs (`Terminal=true` entries,
  pinned shortcuts) arrive via `EXTRA_COMMAND` on the same URI.
- `LauncherActivity`: floating popup window (`Theme.Tawc.Launcher`,
  `sizePopupWindow`), search row `[←][search][⋮]`, finishes on launch,
  forces the IME up on entry. Its ⋮ has Show hidden (N) and Add entry….
- Session notification tap opens `MainActivity`.

## Design

### Panes

`MainActivity` keeps the `DrawerLayout` + `NavigationView` and hosts
exactly one pane in the body `FrameLayout`, plus the FAB. Panes are
plain view controllers (imperative Kotlin, no Fragments, matching the
codebase):

| Pane | When | Top row |
|---|---|---|
| Intro | no installs | `[≡] TAWC [⋮]` — logo, one-line blurb, accent **Install distro** button. ⋮: Task manager, Settings. |
| Info | open distro not READY (INSTALLING / UNINSTALLING / FAILED / CORRUPT) | `[≡] <label> [⋮]` — `DistroInfoActivity`'s content inline (see below) |
| Terminal | READY + tawcroot, and the chosen pane is terminal | `[≡][tabs…][+][⋮]` (the existing `TerminalTabBar`, fixed dark palette) |
| Apps | READY otherwise (chosen pane apps, or a non-tawcroot debug install) | `[≡][Search <label>][⋮]` (the existing search row; `←` becomes `≡`) |

No `MaterialToolbar` on any pane: each pane's top row is its chrome, all
the same height, so the FAB toggle doesn't jump. Where the distro name
shows: the info row title, the search field's hint (`Search Debian sid`),
the tab strip of a pending terminal (see "Pending"), and the drawer's
checked row. An in-use terminal shows only its tabs, as today. Keep
`buildDrawerScreen` but let it take the top row from the pane instead of
building a toolbar.

**Info pane.** A distro that isn't READY has no shell and no app list,
so the home screen shows what Distro info shows: the info rows, failure
text, the size probe, and the red Delete button, with the state row
linking to the live `LogScreenActivity` op for INSTALLING / UNINSTALLING
(the home screen's tap today). Extract `DistroInfoActivity`'s content
builder into a `DistroInfoView` that both the activity (still reachable
from ⋮ for any install) and the pane use; the pane re-renders on
`onResume` the way the activity does. FAB hidden. The chosen pane is
*not* overwritten, so the terminal comes back once the install is READY
or the user switches to a READY distro. A fresh install therefore opens
on its own progress and turns into a prompt when done.

### Chosen pane state

`Settings.homePane: terminal | apps` (pref `home_pane`, default
`terminal`; `TestStore` starts at the default). One global value, not
per distro — `sid+term → arch+term → arch+apps → sid` lands on
`sid+apps`. Written only by the FAB and the ⋮ "Apps" / "Terminal" items.
Read in `onResume` and on every distro switch, next to
`OpenDistro.resolve`.

### FAB

- Apps pane: terminal icon (`ic_terminal`), shown only when the terminal
  is possible (READY + tawcroot). Tap → terminal pane (reattaches in-use
  sessions if any, else spawns a pending shell).
- Terminal pane, pending: apps icon (new `ic_apps` grid drawable). Tap →
  kills the pending shell, shows the apps pane.
- Terminal pane, in use: no FAB (it would sit on live output). ⋮ gets
  **Apps** instead.

### ⋮ menu (top right)

`PopupMenu` anchored to the pane's ⋮ button, contents assembled per
pane from three groups:

1. Per-distro (shared with the drawer row menu, see below): Distro info,
   Run command… (READY). Delete stays on Distro info only.
2. Pane: apps → Show hidden (N), Add entry… (editable methods); terminal
   in use → Apps.
3. App: Task manager, Settings.

The launcher's own ⋮ is removed; one ⋮ per screen.

### Drawer

As today (checkable row per install, divider, Install new distro), plus:

- A trailing ⋮ on each distro row via `MenuItem.setActionView`
  (`NavigationView` renders action views at the row end). Tap → the
  per-distro `PopupMenu` (group 1 above) for *that* install without
  switching to it, so deleting an unused distro is drawer ⋮ → Distro
  info → Delete.
- Rows with live in-use sessions show ` · N terminals` (from
  `TerminalSessions.list(id).size`) so shells left running in another
  distro are findable. Non-READY keeps its ` · state` suffix.

### Terminal pane

`TerminalActivity`'s body moves into `TerminalPane` (in `terminal/`),
which implements `TerminalViewClient` and `TerminalSessionClient` (the
`Activity.onKeyUp` signature clash noted in `TerminalActivity` goes
away). The pane takes the activity for IME/clipboard and a `distroId`,
and is rebuilt on distro switch.

**Pending vs in use.** Only the shell the pane auto-spawns on show is
*pending*; it becomes *in use* on the first input that reaches it: any
`onKeyDown` / `onCodePoint` client callback (these gate every
`TerminalView` write path except autofill — `onKeyDown`,
`inputCodePoint`, extra keys route through them) or a paste. Output alone
(bashrc noise, the prompt) does not count. A `+` tab and a command tab
are in use from birth; pressing `+` on a pending pane promotes the
pending shell too (the user asked for two shells).

| | Pending | In use |
|---|---|---|
| `SessionHolds` | none — no service, no notification | `Reason.Terminal` as today |
| Tab strip | hidden; the distro label sits where tabs would be | tabs as today |
| FAB | Apps | none (⋮ → Apps) |
| `keepScreenOn` | off | on |
| Pane switch / distro switch | shell killed | shells keep running, pane detaches |
| Activity `onDestroy` (finish, recreate, swipe) | killed | untouched — notification Exit is the kill switch |
| Exit (notification) | killed | killed, as today |
| Shell exits or its tab is closed with × | see below | tab closes; when it was the last, `finishAndRemoveTask` — closing the app, as today. Reopening spawns a pending shell. |

A pending shell that dies (nothing typed yet) is the broken-`chsh` case
from notes/terminal.md; finishing there would make the app unopenable.
The pane keeps the transcript with the exit line and a tap or `+`
respawns. This is the one exception to "last shell exit closes the app".

Registry: `TerminalSessions` gains a per-id `pending: TerminalSession?`
slot without a hold (`setPending`, `pending`, `promote(id)` which moves
it into the list and acquires the hold, `killPending(id)`). Keeping it
in the registry rather than the pane means an activity recreation
(uiMode change; rotation is handled by `configChanges`) reattaches
instead of respawning. `DetachedTerminalClient` also clears the pending
slot on exit.

**Stray tail.** `SessionService`'s stray scan runs when the last hold
releases; a pending bash is a guest process with no hold and would keep
the service up as "1 background process". `ProcessScanner.scan` takes an
excluded-pid set; the service passes the pending sessions' pids
(`TerminalSession.getPid()`). A pending shell has no children by
definition, so pids suffice. Assert in `lazy_compositor::test_session_holds_*`.

**Uninstall of the open distro.** State flips to UNINSTALLING; the
resume/refresh path sees non-READY, tears the terminal pane down and
kills the pending shell before the uninstall reaches the rootfs. In-use
sessions: unchanged from today.

### Apps pane

`LauncherActivity`'s body moves into `AppsPane` (in `launcher/`). Drops:
`Theme.Tawc.Launcher`, `tawc_popup_bg`, `sizePopupWindow`, the `←`
button, `finish()` on launch, the per-instance `launched` guard
(becomes a short debounce reset in `onResume`). Keeps: the scan on
`Dispatchers.IO`, `LauncherEntry.filter`, hide/unhide, long-press
actions, the editor round-trip (the activity owns the
`registerForActivityResult` launcher and hands it to the pane).

After a launch the query is cleared and the IME dropped; the
`CompositorActivity` comes forward and the apps pane is what the user
returns to. Rescan on every show, distro switch and `onResume` — the
user installs packages from the terminal pane and expects them to
appear.

IME: both panes request the soft keyboard when shown (terminal via
`showSoftKeyboard`, apps via the search field), including cold start. The
window is `adjustResize`; the body pads system bars + IME so the terminal
grid and the list shrink above the keyboard (today's `TerminalActivity`
inset code, applied to the drawer body).

### Intents and entry points

`MainActivity` gets `launchMode="singleTask"` and `configChanges=
"orientation|screenSize|keyboardHidden"` (what `TerminalActivity` has).
`onNewIntent` handles:

- `EXTRA_DISTRO` + `EXTRA_COMMAND` + `EXTRA_LABEL` (from `EntryLauncher`
  for `Terminal=true` entries and pinned shortcuts): set the open distro,
  show the terminal pane, open the command tab (in use). Same
  consume-once rule as today (`removeExtra`, `savedInstanceState` means
  restore).
- Notification tap: plain launch, lands on the last pane.

`TerminalActivity`, `LauncherActivity`, the `tawc://terminal/` URI and
the two manifest entries are deleted. `LaunchErrorActivity`,
`DesktopFileEditorActivity`, `ShortcutLaunchActivity`,
`DistroInfoActivity` (content moved to `DistroInfoView`, Delete stays) and Settings' distro
card are unchanged.

### Back and recents

Back with a distro open always `moveTaskToBack` (drawer open → closes
the drawer first, as now). On the intro pane, default back. One recents
card for the app; swiping it kills pending shells only (see the table).

## Steps

1. `Settings.homePane` + `TestStore` default; `TerminalSessions` pending
   slot + `promote`/`killPending`; `ProcessScanner.scan` exclusion set
   and `SessionService` passing pending pids. JVM-unit-test the registry
   changes.
2. `Scaffold.kt`: `buildDrawerScreen` variant that takes a pane-supplied
   top row (no toolbar) and pads the body for IME insets; `ic_apps`
   drawable.
3. `TerminalPane` from `TerminalActivity` (client callbacks, tabs, extra
   keys, font size, clipboard), with the pending flip, tabless pending
   bar, ≡ and ⋮ buttons in `TerminalTabBar`, last-shell finish and
   dead-pending restart.
4. `AppsPane` from `LauncherActivity`, ≡ in the search row, label in
   the hint, ⋮ moved to the shared menu. `DistroInfoView` extracted from
   `DistroInfoActivity`; info pane.
5. `MainActivity`: pane host, FAB, ⋮ assembly, drawer row ⋮ + terminal
   count, `onNewIntent` command path, back handling, `singleTask` +
   `configChanges`. `EntryLauncher` and `ShortcutLaunchActivity` target
   `MainActivity`.
6. Delete `TerminalActivity`, `LauncherActivity`, their manifest entries,
   `Theme.Tawc.Launcher`, `tawc_popup_bg`, `hint_search_apps` stub
   strings; add pane/menu strings.
7. Debug broker: `home-pane` action (`get` / `terminal` / `apps`) and
   `terminal-state` (`pending` / `inUse:N` for the open distro), so tests
   and `test-init` can put the home screen in a known state without
   screenshots.
8. Verify on the `.tawctarget` device (see below).
9. Notes.

## Verification

- 0 installs → intro; install → info pane of the new slot with the live
  log link until READY, then a prompt.
- Cold start: prompt + keyboard, no notification (`session-state`
  empty), `dumpsys activity services` shows no `SessionService`.
- Type one key → tab appears, FAB gone, notification up. `exit` (and
  separately × on the last tab) → app closes, notification gone;
  reopen → pending prompt, no "1 background process" within the
  stray-tail window.
- Pending → FAB → apps: `ps` inside the rootfs shows no leftover bash.
- Two distros: in-use shells in A, switch to B, drawer shows
  ` · 1 terminal` on A, B gets its own pending shell; switch back
  reattaches A's tabs.
- `Terminal=true` entry and a pinned shortcut open a command tab from
  cold start, from the apps pane, and while another distro is open.
- Rotate on each pane; dark-mode toggle recreates the activity and
  reattaches (pending respawned, in-use intact).
- Swipe the card with an in-use shell: notification stays, tap it → tabs
  are back. Exit action kills everything.
- Uninstall the open distro from Distro info while its terminal pane
  is pending → info pane with the uninstall log link; uninstall a
  non-open distro via drawer ⋮ → Distro info without the pane changing.
- FAILED install open → info pane with failure text and Delete.
- Non-tawcroot (proot, debug build) install: apps pane, no FAB, no
  Terminal item.
- `scripts/run-integration-tests.sh launcher lazy_compositor` green.

## Notes to update

- `notes/android.md` "Home screen" and "Kotlin App Structure"
  (`MainActivity` hosts panes; `TerminalActivity`/`LauncherActivity`
  gone).
- `notes/terminal.md`: pending/in-use, the pane, entry points, the
  broken-`chsh` recovery now being in-app.
- `notes/launcher.md`: pipeline step 1 and the ⋮ location; "Future UX".
- `notes/session-service.md`: pending exclusion from the stray scan;
  swipe no longer kills.
- `notes/installation.md` / wherever Distro info is described: the
  shared `DistroInfoView`.
- `notes/multi-activity.md` "MainActivity role"; `notes/exec-broker.md`
  for the new actions.
- `notes/licensing.md` only if the GPL extra-keys shim grows a subclass.

## Open questions

- **Swipe semantics.** The table says a recents swipe leaves in-use
  shells running (termux behaviour). Today's per-distro card swipe kills
  them. Confirm.
- **Keyboard on cold start** for both panes, as termux does, or only when
  the user reaches a pane through the FAB.
- **Dead pending shell** keeps the app open with a restart affordance
  rather than finishing (the one exception to last-shell-exit closing
  the app). Confirm.
