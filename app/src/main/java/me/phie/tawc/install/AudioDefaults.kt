package me.phie.tawc.install

/**
 * Guest-side files for the audio stack, shipped into every rootfs by
 * [AudioInstallProvider] and (re)applied on app upgrade like every other
 * provider.
 *
 * Two files, because the rootfs has no init and therefore no service
 * manager to own them:
 *
 *  - the PipeWire config that creates the output sink, and
 *  - a starter script the app runs per install, which brings the
 *    daemons up if they aren't already.
 *
 * The other half of the bridge — draining the FIFO into an
 * `AudioTrack` — is app-side
 * (`me.phie.tawc.audio.TawcAudioBridge`). See notes/android.md "Audio"
 * and plans/audio.md.
 *
 * Package names live with each distro's installer, not here, because
 * they differ per package manager; see `Distro.optionalPackages`.
 */
internal object AudioDefaults {
    /**
     * PipeWire config. The daemon reads this from its
     * `pipewire.conf.d` scan directory, so it must land on that exact
     * path — that scan never covers `pipewire-pulse.conf.d`.
     */
    const val PIPEWIRE_CONF_PATH = "/etc/pipewire/pipewire.conf.d/50-tawc-audio.conf"

    /** Idempotent starter. Not on the guest's PATH by default. */
    const val START_SCRIPT_PATH = "/usr/local/bin/tawc-audio-start"

    /**
     * The FIFO the app creates in its own `share/` dir and every install
     * method binds to `/usr/share/tawc` — so this path *is* the app's
     * `AudioTrack` endpoint, not a copy of it.
     */
    const val ENDPOINT_PATH = "/usr/share/tawc/audio-out-0"

    val PIPEWIRE_CONF_CONTENT = """
        # Installed by TAWC — see notes/android.md ("Audio"). Edits will be
        # overwritten on app upgrade; the guest-visible copy of this file is
        # the supported place to change it.
        #
        # Two modules, both appended to the daemon's default context.modules:
        #
        #  - protocol-pulse, so Pulse clients (pactl/paplay) can reach the
        #    graph. It has to be in the MAIN daemon: /etc/pipewire/
        #    pipewire-pulse.conf.d/ is never scanned, and /usr/sbin/
        #    pipewire-pulse would be a second daemon on a different socket.
        #  - pipe-tunnel in sink mode, which is what actually carries audio
        #    to the phone's speaker.
        #
        # server.address is a LIST, and "unix:native" is the relative
        # spelling that yields ${'$'}XDG_RUNTIME_DIR/pulse/native — the path
        # Pulse clients look for by default. An absolute
        # "unix:/tmp/pulse/native" fails at module init with
        # "no servers could be started: Invalid argument".
        context.modules = [
          {
            name = libpipewire-module-protocol-pulse
            args = {
              server.address = [ "unix:native" ]
            }
          }
          {
            name = libpipewire-module-pipe-tunnel
            args = {
              tunnel.mode = sink
              # Keep the sink running while idle so a session manager that
              # opens/closes the endpoint around playback doesn't churn
              # the FIFO open. Matches what a desktop audio daemon does.
              tunnel.may-pause = false
              pipe.filename = "$ENDPOINT_PATH"
              # Fixed format: PipeWire resamples and remixes for clients,
              # and the app's AudioTrack is built for exactly this.
              audio.format = "S16LE"
              audio.rate = 48000
              audio.channels = 2
              node.name = "tawc_output"
              node.description = "TAWC Android output"
              stream.props = {
                media.class = "Audio/Sink"
              }
            }
          }
        ]
    """.trimIndent()

    /**
     * Starter script. Idempotent by design: with no init there is no
     * supervisor to restart a daemon, and the app re-runs this on every
     * start, so "already running" has to be a normal, quiet outcome.
     *
     * Every daemon is `setsid`-ed with its output redirected to a log so
     * it outlives the spawn AND doesn't hold the spawn's stdout pipe open
     * — otherwise the app's reader would never see EOF and the process
     * would look like it hung.
     */
    val START_SCRIPT_CONTENT = """
        #!/bin/bash
        # TAWC guest audio: bring up the PipeWire stack if it isn't running.
        # Installed by TAWC, invoked by the app once per install per app
        # start. Safe to run repeatedly; exits 0 in every case.
        set -u

        export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp}"
        LOG=/tmp/tawc-audio.log
        say() { echo "${'$'}(date +%T) ${'$'}*" >> "${'$'}LOG" 2>/dev/null; }

        # No packages (an install predating audio support, or an optional
        # package that wouldn't install): say so once and leave. Audio is
        # not required for anything else to work.
        if ! command -v pipewire >/dev/null 2>&1; then
            say "pipewire not installed; guest audio disabled"
            exit 0
        fi

        # pipewire owns the graph; wireplumber is the session manager that
        # elects a default sink (without it, clients have no default and
        # apps that don't pick a target explicitly stay silent).
        if pgrep -x pipewire >/dev/null 2>&1; then
            say "pipewire already running"
        else
            say "starting pipewire"
            setsid pipewire >>"${'$'}LOG" 2>&1 < /dev/null &
            # Wait for the socket before starting wireplumber or anything
            # else: a client that connects before it exists just fails once.
            for _ in ${'$'}(seq 1 50); do
                [ -S "${'$'}XDG_RUNTIME_DIR/pipewire-0" ] && break
                sleep 0.1
            done
        fi

        if pgrep -x wireplumber >/dev/null 2>&1; then
            say "wireplumber already running"
        elif command -v wireplumber >/dev/null 2>&1; then
            say "starting wireplumber"
            setsid wireplumber >>"${'$'}LOG" 2>&1 < /dev/null &
            sleep 0.5
        fi

        exit 0
    """.trimIndent()
}
