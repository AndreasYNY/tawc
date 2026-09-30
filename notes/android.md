# Android Integration

## Wayland Socket Sharing

**With root (chroot):** The compositor creates a Unix socket at a known path and the
chroot client connects directly. Root bypasses SELinux MAC checks on `connect()`.
This is the current development approach.

**Without root (proot, future goal):** SELinux blocks cross-app `connect()` between
`untrusted_app` domains on Android 9+. Two viable solutions:

1. **Binder fd passing (preferred):** Compositor creates a `socketpair()`, passes one end
   to Termux via a ContentProvider or bound Service as a `ParcelFileDescriptor`. No
   `connect()` syscall occurs, so SELinux is never triggered.

2. **Shared UID:** `sharedUserId="com.termux"` makes both apps run as same UID/SELinux
   domain. Deprecated since API 33 but still functional. Limits distribution flexibility.

## Chroot Setup

Install (once, via the dev exec broker; progress streams to your TTY
and the in-app log screen opens automatically):
```bash
scripts/tawc-exec.sh --foreground-app --action install \
    --arg id=arch \
    --arg mirrorProxy=http://127.0.0.1:8080/proxy/
```

Then drive the chroot from the host with:
```bash
scripts/rootfs-run.sh                    # interactive shell
scripts/rootfs-run.sh '<command>'        # run a command and exit
```

`rootfs-run` routes through the dev exec broker's `RUNINSIDE` request
(`tawc-exec --in-rootfs <id>`). The broker reads the install's
recorded method from `metadata.json` and dispatches to the matching
[InstallationMethod.startInside], which builds the bind table and
chroot exec fresh in Kotlin on every call. There is no on-disk
wrapper script and no `adb shell su` in this path — chroot installs
fork `su` from inside the JVM. Generic TAWC Wayland env vars come
from `RootfsEnv.kt` via a `/usr/bin/env -i KEY=VAL …` wrapper around
the in-rootfs `bash -lc`, so nothing inside the rootfs needs to be
on disk between calls.

### Shell quoting

Commands sent through `tawc-exec --in-rootfs` are framed in the
broker wire protocol (length-prefixed argv), so quoting is not an
issue end-to-end. If you ever bypass the broker and use raw
`adb shell su -c '…'` directly, you'll need to handle the layered
quoting yourself:

**Critical quoting rule for `&&` / `||` in `su -c`:** When running compound
commands via adb, the outer shell (mksh) parses `&&` and `||` BEFORE `su` sees
them. This silently runs the second command as shell (uid 2000), not root:

```bash
# BROKEN: mksh splits at &&. cp runs as root, build runs as shell user.
adb shell su -c "cp /tmp/foo /chroot/tmp/ && /chroot/build.sh"

# CORRECT: inner quotes protect && from mksh.
adb shell "su -c 'cp /tmp/foo /chroot/tmp/ && /chroot/build.sh'"
```

Variable expansion like `$0` or `$KSH_VERSION` at any intermediate layer can
give misleading results. The `su` shell on Android is mksh (`/system/bin/sh`),
easily confused with the chroot's GNU bash.

## EGL Context and Surfaces

- An EGL context CAN move between threads (release on old, bind on new), but expensive
- One thread can render to multiple EGLSurfaces via `eglMakeCurrent` switches
- Each switch flushes the pipeline -- overhead per switch
- Recommended: single render thread, one context, switch surfaces per window
- `ASurfaceTransaction` + AHB avoids `eglMakeCurrent` overhead entirely (future opt)

## Multiple Activities

See [multi-activity.md](multi-activity.md) for the full per-window-task plan.
Background facts that informed it:

- All Activities in one app share the same process (single heap, static state, threads)
- One SurfaceView per Activity avoids Z-ordering issues
- Single background render thread maintains list of active surfaces
- Activity launch creates visual transitions -- suppress with
  `overridePendingTransition(0, 0)`
- Activities may be killed under memory pressure -- handle surface loss gracefully

## Kotlin App Structure

The Android app code (`app/src/main/java/me/phie/tawc/`) is split so that
everything talking to the Rust compositor lives in its own package, separate from
the rest of the app's UI/management features.

- `MainActivity.kt` — home screen hosting the intro / info / terminal /
  apps panes (see "Home screen" below). Plain Android UI (no
  fullscreen, no Wayland).
- `OpenDistro.kt` — which install the home screen shows.
- `compositor/` — everything that interacts with the Rust compositor:
  - `CompositorActivity.kt` — fullscreen immersive Activity that owns the
    `SurfaceView`, dispatches touch/IME, and registers the test broadcast
    receiver. Started via Intent from `MainActivity`. Uses the
    `Theme.Tawc.Compositor` style.
  - `NativeBridge.kt` — JNI surface (matches Rust JNI symbols
    `Java_me_phie_tawc_compositor_NativeBridge_*` and `find_class
    "me/phie/tawc/compositor/NativeBridge"` in `compositor/src/lib.rs`).
  - `TawcInputConnection.kt` — IME bridge.
- `install/` — Kotlin implementation of the chroot install / run /
  destroy logic. The rootfs is stored under
  `/data/data/me.phie.tawc/distros/<id>/rootfs/` so uninstalling
  the app reclaims it. The host-side counterpart is
  `scripts/rootfs-run.sh`, which routes through the dev exec broker
  to the same [InstallationMethod.startInside]. See
  [installation.md](installation.md) for the package map, the
  broker `--action install/uninstall` CLI, and the Android 14 FGS
  rationale.

When adding new app features (settings, app launcher, …), put them in
their own packages under `me.phie.tawc.*` rather than mixing them into
the compositor or install packages.


## Home screen

Opening the app lands in a shell, termux-style. `MainActivity`
(`singleTask`, `configChanges` for rotation, `adjustResize`) hosts
exactly one pane for the one open distro, plus a FAB. Panes are plain
view controllers, no Fragments; each supplies its own top row (48dp
`paneTopRowHeightPx`, except the apps header's 64dp), so there is no toolbar.

| Pane | When | Top row |
|---|---|---|
| Intro | no installs | `[≡] TAWC [⋮]`, logo, blurb, accent Install |
| Info (`DistroInfoView`) | open distro not READY | `[≡] <label> [⋮]`; state row links to the live op log |
| Terminal (`terminal/TerminalPane`) | READY + tawcroot, pane = terminal | `[≡][tabs… +][⋮]` (dark `TerminalTabBar`) |
| Apps (`launcher/AppsPane`) | READY otherwise | `[≡] <label> [🔍][⋮]`; 🔍 opens a search field below |

- **Open distro:** `Settings.openDistroId` (pref `open_distro`; the test
  store starts null). Always read through `OpenDistro.resolve` (stored
  id → first READY → first install → null, written back). Written by
  the drawer, `InstallActivity` on Install (a fresh install opens on its
  own progress), and command launches.
- **Chosen pane:** `Settings.homePane` (`home_pane`, default
  `terminal`), one global value. Written by the FAB, ⋮ Apps,
  starting an install (a new distro opens on its progress, then its
  prompt) and the debug `home-pane` action. The info pane never
  overwrites it. A command launch forces the terminal up without
  writing it.
- **FAB:** apps pane → terminal (only when possible; hides while the
  grid scrolls down, returns on scroll up); pending terminal →
  apps (`ic_apps`, lifted above the extra keys); in-use terminal → none
  (⋮ → Apps).
- **⋮:** one `PopupMenu` per screen, top to bottom: pane items (apps:
  Show hidden (N) when N > 0, Add entry…; in-use terminal: Close all, Apps — the
  one pane toggle, since no FAB shows there), Settings (opened on that
  distro's card, `SettingsActivity.EXTRA_ID`), Run… (READY), Task
  manager, Distro info.
- **Drawer:** a checkable row per install (` · state` for non-READY,
  ` · N terminals` for live shells, re-read as the drawer opens), each
  with a trailing ⋮ (Settings, Run…, Distro info for *that* install, no
  switch); then Install new distro (no divider). The open distro's row has a
  neutral fill and a left accent strip (`drawable/nav_item_bg`).
  Opening the drawer drops the IME. Drawer and popups use
  `ThemeOverlay.Tawc.Surfaces`.
- **IME:** both panes ask for the keyboard when shown (cold start, FAB,
  distro switch); the drawer root pads system bars + IME. The terminal
  pane also darkens the bar bands and turns their icons light.
- **Back:** closes the drawer, else `moveTaskToBack` (intro: default).
- **Intents:** `EntryLauncher` sends `EXTRA_DISTRO` + `EXTRA_COMMAND` +
  `EXTRA_LABEL` for `Terminal=true` entries (and pinned shortcuts via
  `ShortcutLaunchActivity`); consumed once (`removeExtra`;
  `savedInstanceState` means restore). The session notification is a
  plain launch.
- **Settings:** the first card, titled with the open distro's label, holds
  its per-install settings (ando toggle, Manage binds; READY/FAILED only,
  else a one-line note), rebuilt in `onResume`. Omitted with no install.
  Every other card is global. `DistroInfoActivity` (same
  `DistroInfoView`) stays reachable from ⋮ for any install and holds
  Delete.
- Terminal lifecycle (pending vs in use): [terminal.md](terminal.md).
- Open ideas: a permanent drawer on wide screens.

## Audio

**Status (2026-09-30): playback works.** Guest PCM is bridged to an
Android `AudioTrack`, verified on the physical device with Firefox
playing audio. Rootfs side is a PipeWire-first stack bridged through an
app-owned FIFO under `/usr/share/tawc/`; see
[audio.md](../plans/audio.md). Still missing: **capture** (mic), and
**Pulse client support** (native PipeWire and ALSA both work, so this
bites Pulse-only clients only). Details below.

There is deliberately **no `/dev/snd`** handed to the guest. That was the
original symptom — apps played video with no sound — and the design
decision behind the bridge: app audio belongs behind
`AudioTrack`/`AudioRecord`/AAudio so Android keeps control of permissions
and routing, rather than exposing fake host soundcards. Measured on the
physical device (Redmi 21051182G, Arch Linux ARM):

- `/dev/snd` exists on the host (`u:object_r:audio_device:s0`) but is
  absent from the guest `/dev`, so `stat /dev/snd` is `Permission denied`
  and `cat /proc/asound/cards` likewise.
- The guest `/dev` is a **fresh tmpfs** (`tmpfs /dev ... mode=755`), not
  the host's: `/dev/pts`, `/dev/shm`, `/dev/null`, `/dev/urandom`,
  `/dev/random`, `/dev/ptmx` are present, but nothing audio-related is.
  `TawcrootMethod.bindSpecs` asks for `BindSpec("/dev", "/dev")`, and
  tawcroot serves that from its own curated node set rather than
  bind-mounting the host tree wholesale.
- There are **no** `avc: denied` records for `audio_device` or
  `/dev/snd` in logcat, so this is not a policy denial tawc could
  request its way past — an unprivileged app simply does not get the
  audio device nodes. Consequence: **`aplay -l` reports "no soundcards
  found" and that is expected**, not a fault. The `pipewire-alsa` config
  routes the `default` PCM into PipeWire without any hardware, so
  clients that open `default` work fine.

So the absence of `/dev/snd` is by design, not a regression, and it is
not what blocks audio any more.

### The tawcroot half is now unblocked (2026-09-30)

Getting the rootfs stack up (`pipewire` 1:1.6.9, `wireplumber` 0.5.17,
`pipewire-pulse`, `pipewire-alsa`, `alsa-utils`) first hit a wall that
was neither audio- nor PipeWire-specific: *every* PipeWire client hung
on connect. `pw-cli ls Node` timed out at `rc=124` with no client-side
error, and the daemon logged one
`flatpak check failed: No such file or directory` per connection.

Cause: `module-access` probes each client for Flatpak confinement by
opening `/proc/<client-pid>/root`, and tawcroot contained that link
because the kernel resolves it to the *host* root (tawcroot never
chrooted), which is outside the guest's view. A failed directory open is
fatal to that probe in a way a missing `.flatpak-info` is not, so the
client never got `permissions` and stayed busy forever. Fixed in
tawcroot by rewriting the root link to the guest root for any process
the guest can see, not just `self` — see
notes/tawcroot/path-translation.md §"`/proc` magic-link containment".
There is no PipeWire-side workaround: `module.access = false` just
leaves nothing to resolve `permissions`.

State after the fix, measured in the guest on the physical device:
`pipewire` and `wireplumber` both run, clients connect, and
`pw-cli ls Node` returns `rc=0`. `libpipewire-module-pipe-tunnel.so` is
present, so the `/usr/share/tawc/audio-out-0` design is unblocked.

With that fixed, the graph held just `Dummy-Driver` and
`Freewheel-Driver` — no output device, because there is no `/dev/snd`.
So the Android-side bridge became the only thing left, which is the next
section. (`wireplumber` runs here with no D-Bus session bus and no
RTKit, so realtime scheduling is disabled and it skips every
dbus/portal/mpris component — expected, not a fault.)

### Playback: how it works, and what is left (2026-09-30)

`TawcAudioBridge` (app/src/main/java/me/phie/tawc/audio/) is the
playback half: a `tawc-audio-out` thread reads the FIFO and writes it
into an `AudioTrack`. Two things make it small:

- **The FIFO is already the app's file.** Every install method binds the
  app-private `share/` directory to `/usr/share/tawc`, so the endpoint
  the guest opens as `/usr/share/tawc/audio-out-0` *is*
  `<shareDir>/audio-out-0` — same inode, no copy, and no SELinux or uid
  boundary to cross (guests are this process's children). The app
  `mkfifo`s it via `Os.mkfifo`, and refuses to touch a non-FIFO
  squatting on the name.
- **No ring buffer.** The FIFO is opened **O_RDWR** so the open never
  blocks and a closing sink never reports EOF (a session manager opens
  and closes the endpoint around playback, so a read-only fd would spin
  in a reopen loop). `AudioTrack.write` then blocks once the track
  buffer is full, which makes Android's output clock the pacer and
  throttles PipeWire upstream instead of buffering here.

Lifecycle is **process-scoped in `TawcApplication`**, deliberately not
`CompositorService` or `SessionService`: a terminal tab runs a live
session with neither bound, so a hook in either would mean no audio at
all in the common case. The class also carries a process-wide guard,
because two bridges on one FIFO don't duplicate audio — the kernel gives
each chunk to exactly one reader, so every sample would be split across
two tracks. That is a silent corruption rather than a loud failure, and
an early build did exactly that.

Guest config, `/etc/pipewire/pipewire.conf.d/50-tawc-audio.conf`: a
`pipe-tunnel` sink (`tunnel.mode = sink`, s16le/stereo/48k,
`node.name = tawc_output`) plus `libpipewire-module-protocol-pulse`.
The pulse module has to be in the **main** daemon's `context.modules`:
`/etc/pipewire/pipewire-pulse.conf.d/` is never scanned (the daemon only
reads `pipewire.conf.d`) and `/usr/sbin/pipewire-pulse` would be a second
daemon on a different socket. Note `server.address` is a *list*, and
`"unix:native"` is the spelling that yields the
`$XDG_RUNTIME_DIR/pulse/native` path Pulse clients look for; an absolute
`unix:/tmp/pulse/native` fails earlier with `no servers could be started:
Invalid argument`.

**Verified on the physical device.** `pw-play` of a generated WAV
produces an active `AudioTrack` in `dumpsys media.audio_flinger`:

```
1 Tracks of which 1 are active
  Id Active Client  Port S Flags   Format Chn mask  SRate ST Usg CT  Server FrmCnt FrmRdy F Underruns Latency
 433   yes  20857   423 A 0x001 00000001 00000003 48000  3   1  2   0021D480   7696  2944 A     92672        0  209.32
```

Active, bound to mixer port 423, PCM_16BIT/stereo/48000 exactly as
configured, `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`, **0 underruns**, frame
counter advancing.

**End-to-end with a real app: Firefox**, confirmed audible on the
device (SoundCloud, 2026-09-30). It reaches the sink over **ALSA**, not
Pulse — the graph shows

```
node.name = "alsa_playback.firefox"
media.class = "Stream/Output/Audio"
```

which is `pipewire-alsa` handing a cubeb client into PipeWire, then the
same FIFO → `AudioTrack` path. So the production path for cubeb apps is
ALSA, and the Pulse defect below does not affect them.

**Not yet working: Pulse clients.** `pactl`/`paplay` get `Connection
failure: Connection terminated` — the server logs the client connecting
and disconnecting inside a single millisecond, and never registers it
as a PipeWire client. Tracked in
[../issues/pipewire-pulse-drops-client-immediately.md](../issues/pipewire-pulse-drops-client-immediately.md),
which also records everything already ruled out.

**This does not block real apps.** There are three client paths, and two
work:

| path | status |
| --- | --- |
| native PipeWire (`pw-play`) | works |
| ALSA (`aplay`, cubeb fallback) | works |
| Pulse (`pactl`, `paplay`) | broken |

Firefox and Electron use cubeb, which *prefers* Pulse but falls back to
ALSA, and `pipewire-alsa` makes that fallback work. **Firefox playback
is confirmed on the physical device (2026-09-30)** — it shows up in the
graph as `alsa_playback.firefox`, and `aplay -D default` works for any
ALSA client. So Discord and most games should be fine too.

Two things follow, and both matter:

- **Don't drop `pipewire-alsa`.** It is currently the reason Firefox has
  audio at all. Removing it as "unused" would silently break every
  cubeb client.
- **`aplay -l` reporting "no soundcards found" is expected, not a
  second bug.** There is no `/dev/snd` (see the top of this section),
  but the `pipewire-alsa` config routes the `default` PCM into PipeWire
  without needing hardware. Clients that *enumerate* soundcards see
  none; clients that open `default` work.

Capture (`audio-in-0`, `AudioRecord`, `RECORD_AUDIO` plus a user toggle)
remains a separate milestone and is deliberately not implemented.

### Fresh installs get audio automatically (2026-09-30)

Verified end-to-end on a **freshly installed Debian sid** slot (no
manual steps: install, then use it).

Three pieces, all installed by `TawcInstaller.installAll` on every app
start, so a config change reaches existing installs too:

- **`AudioInstallProvider`** copies two files into every rootfs:
  `/etc/pipewire/pipewire.conf.d/50-tawc-audio.conf` (the sink) and
  `/usr/local/bin/tawc-audio-start` (the starter). Content lives in
  `AudioDefaults`, same pattern as `ShellDefaults`.
- **`Distro.optionalPackages`** is a new, *best-effort* package set:
  `pipewire`, `wireplumber`, `pipewire-pulse`, `pipewire-alsa`,
  `alsa-utils`. It is a separate `pacman`/`apt`/`xbps` invocation whose
  failure is logged and swallowed, because it must not be able to fail
  an install — the whole point of the split from `basePackages`. Audio
  degrades the same way: the starter exits 0 and says so in its log when
  `pipewire` is absent.
- **`GuestAudio.start()`** runs the starter once per install, because
  the rootfs has no init to do it. It uses
  `InstallationMethod.startInside` rather than
  `UserRootfsSession.startInside`, which ties a `SessionHolds`
  reference to the spawned process's exit — wrong here, since the script
  exits immediately while the daemons live on. They are picked up
  afterwards by the normal stray scan (their `exe` is inside the
  rootfs), like any daemon a guest leaves behind.

**Ordering matters and is load-bearing.** `TawcApplication`'s startup
thread runs the bridge first (it `mkfifo`s the endpoint synchronously),
then `TawcInstaller.installAll` (which writes the script), then
`GuestAudio.start`. Reverse either and it breaks: the starter is skipped
on the first start after an upgrade, or `pipewire` wins the race against
`mkfifo` and `pipe-tunnel` can't open the FIFO for the life of the
process.

Result on the fresh slot: `tawc_output` present in the graph, both
daemons up, and an `AudioTrack` active in `dumpsys media.audio_flinger`
at PCM_16BIT/stereo/48000 with **0 underruns** while
`aplay -D default /usr/share/sounds/alsa/Front_Center.wav` played.

### Two things to know about this wiring

- **The starter's exit code is not a health signal.** It exits 0 when
  run directly, but the daemons detach and the spawn wrapper is
  `exec setsid`, so what the app waits on isn't reliably the script's
  own exit — `rc=137` was observed with audio working fine (consistent
  with issues/phantom-process-killer-kills-rootfs-processes.md). The app
  logs it at info and points at the guest's `/tmp/tawc-audio.log`;
  retries are automatic because the script is idempotent.
- **One FIFO is shared by every install, so is one phone speaker.** With
  two slots there are two `pipewire` graphs, both holding the same
  `audio-out-0` FIFO open. That's right for the common case (one active
  distro) and matches the plan's single app-owned endpoint, but two
  slots playing *simultaneously* would interleave into one track. A
  per-install endpoint would be the fix if that ever matters.

Also note `pactl`/`paplay` are **absent on Debian** even with audio
working: Arch's `libpulse` ships the client tools, Debian puts them in
`pulseaudio-utils`. Not needed for playback, only for poking at the
graph from the guest.
