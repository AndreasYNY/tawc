# pipewire-pulse accepts a Pulse client then instantly drops it (guest, tawcroot)

Found 2026-09-30 while landing the guest audio bridge. **The Pulse
compatibility layer is broken; the other two client paths both work.**

| client path | status |
| --- | --- |
| native PipeWire (`pw-play`) | works |
| ALSA (`aplay`, and cubeb clients that fall back to it) | works |
| Pulse (`pactl`, `paplay`) | **broken** |

This is a compatibility wart, **not** a blocker for real apps: Firefox
and Electron use cubeb, which prefers Pulse but falls back to ALSA, and
`pipewire-alsa` makes that fallback work. Firefox playback is confirmed
working on the physical device (2026-09-30) — it appears in the graph as
`alsa_playback.firefox`. Don't read this as "Firefox audio is broken."

## Symptom

Guest: pipewire 1:1.6.9-1, wireplumber 0.5.17-2, libpulse client 17.0.

```
$ pactl info
Connection failure: Connection terminated
$ paplay --raw /tmp/p.wav
Connection failure: Connection terminated
```

Native clients are unaffected — `pw-cli ls Node` returns `rc=0` and
`pw-play` plays audibly. ALSA clients are unaffected too, via the
`pipewire-alsa` plugin's `default` PCM:

```
$ aplay -D default -f S16_LE -r 48000 -c 2 /tmp/a.raw
Playing raw data '/tmp/a.raw' : Signed 16 bit Little Endian, Rate 48000 Hz, Stereo
```

Note `aplay -l` reports "no soundcards found" and that is **expected,
not a second bug**: there is no `/dev/snd` in the guest (see
[../notes/android.md](../notes/android.md), "Audio"), but the
`pipewire-alsa` config routes the `default` PCM into PipeWire without
needing hardware. Anything that *enumerates* soundcards will see none;
anything that opens `default` works.

The `pipe-tunnel` sink `tawc_output` exists and `wireplumber` selects it
as `default.audio.sink`.

## What is NOT the cause

Ruled out by measurement, so nobody re-walks them:

- **Not the tawcroot `/proc/<pid>/root` fix.** The daemon log during a
  failing connect shows
  `pw_check_flatpak() no .flatpak-info, client on the host` — the probe
  takes the healthy path. Native clients connect fine.
- **Not the socket name or address.** Reproduced identically both ways:
  `server.address = [ "unix:native" ]` (socket at
  `$XDG_RUNTIME_DIR/pulse/native`) and the module's own default
  (`native-pipewire-0`, with the client pointed at it via
  `PULSE_SERVER`). An absolute `unix:/tmp/pulse/native` in the list fails
  earlier and differently, with
  `pulse-server.c: no servers could be started: Invalid argument`, so
  don't use that form.
- **Not a missing `/etc/pipewire/pipewire-pulse.conf.d/` symlink.** Adding
  it changes nothing: the daemon only scans `pipewire.conf.d`, so the
  module has to be in the main daemon's `context.modules`. That is what
  the working config does.
- **Not module load order or a missing dependency.** The module initialises
  and logs its parsed defaults
  (`pulse.min.quantum = 256/48000`, `pulse.default.format = F32LE`,
  `pulse.idle.timeout = 0`, …). The set it needs (`adapter`,
  `client-node`, `metadata`, `protocol-native`, `rt`) is all already in
  the daemon's default list.
- **Not the auth cookie.** The server writes a fresh 256-byte cookie to
  `$HOME/.config/pulse/cookie` on every attempt, and deleting it first
  changes nothing.
- **Not a seccomp/SELinux denial on the connection path.** `tawc-native`
  logs nothing; the only `avc: denied` in the window is `gmain` reading
  `/` on the `device`-labelled tmpfs, which is ALSA enumerating the
  non-existent `/dev/snd` and is unrelated.
- **Not a stray second daemon.** No `pipewire-pulse` or `pulseaudio`
  process is running.

## The observation that matters

With `PIPEWIRE_DEBUG=4` on the daemon, a whole `pactl info` produces
seven log lines, all inside the same millisecond:

```
[D] mod.protocol-pulse | server.c: 404 on_connect()          server: new client fd:42
[D] mod.protocol-pulse | flatpak-utils.h: 105 pw_check_flatpak() no .flatpak-info, client on the host
[I] mod.protocol-pulse | server.c: 340 on_client_data()      server: client [(null)] disconnected
[D] mod.protocol-pulse | client.c:  80 client_detach()      client: detaching from server
[D] spa.system          | system.c:   69 impl_close()       close fd:42
[D] mod.protocol-pulse | client.c: 132 client_free()        client: free
[D] pw.work-queue       | work-queue.c: 212 ...              no deferred found for object id:4294967295
```

The server never registers the client as a PipeWire client: `pw-cli ls
Client` counts 4 both before and during a `pactl` connection. So the
failure is inside the Pulse handshake, before a PW client would exist —
libpulse hangs up on the server's first reply rather than on anything
the guest denies.

## Next step

Priority is low: with the ALSA fallback in place, nothing shipping is
blocked. Fix it when a Pulse-only client actually matters, or when
latency/robustness through the Pulse path becomes the better route.

The server's first reply is the AUTH packet, so that is where to look
next. Two cheap untried angles:

1. Get the *client* side talking. `pactl` has no `--log-level`, and
   `PULSE_LOG_LEVEL`/`PULSE_LOG_TARGET` produced nothing on
   libpulse 17.0, so a packet capture of `/tmp/pulse/native` (or
   building `libpulse` with `pa_debug` wired up) is the reliable way to
   see what libpulse rejects.
2. Suspect the tawcroot boundary again but from the other side: the
   handshake's first client→server write is the point where the two
   processes first exchange data, so a path or syscall that only matters
   for a fresh AF_UNIX peer is still a live hypothesis. The
   `guest_…`/tawcroot syscall surface, not the daemon's event loop, is
   the thing that has never been exercised by this code path.

## Working config, for reference

`/etc/pipewire/pipewire.conf.d/50-tawc-audio.conf` in the guest:

```ini
context.modules = [
  { name = libpipewire-module-protocol-pulse
    args = { server.address = [ "unix:native" ] } }
  { name = libpipewire-module-pipe-tunnel
    args = { tunnel.mode = sink
             tunnel.may-pause = false
             pipe.filename = "/usr/share/tawc/audio-out-0"
             audio.format = "S16LE" audio.rate = 48000 audio.channels = 2
             node.name = "tawc_output"
             node.description = "TAWC Android output"
             stream.props = { media.class = "Audio/Sink" } } }
]
```

The `pipe-tunnel` half of this is the working half. See
[../notes/android.md](../notes/android.md) ("Audio").
