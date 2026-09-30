package me.phie.tawc.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Playback half of the guest audio bridge.
 *
 * The rootfs side is a PipeWire `pipe-tunnel` sink writing fixed-format
 * PCM into a FIFO. Every install method already binds the app-private
 * `share/` directory to `/usr/share/tawc`, so the FIFO the guest opens as
 * `/usr/share/tawc/audio-out-0` is this process's own
 * `<shareDir>/audio-out-0` — the same inode, not a copy. We create and
 * remove it so a stale endpoint from a killed session can't wedge the
 * daemon, which is the reason the app owns the name rather than the
 * guest.
 *
 * The guest runs as this process's own children, so there is no SELinux
 * or uid boundary between the two ends: both open the same file with the
 * app's credentials.
 *
 * Format is fixed at s16le / stereo / 48 kHz. PipeWire resamples and
 * remixes for clients, so accepting a format matrix here would buy
 * nothing; see plans/audio.md.
 *
 * Two details are load-bearing:
 *
 *  - The FIFO is opened **O_RDWR**, deliberately. A read-only open blocks
 *    until a writer appears and then reports EOF on every close of the
 *    sink, which for a session manager that opens and closes the endpoint
 *    around playback means a busy reopen loop. Holding the write end
 *    ourselves makes the open non-blocking and turns "nobody is playing"
 *    into an ordinary blocking read.
 *  - There is **no ring buffer**. [AudioTrack.write] blocks once the
 *    track buffer is full, so Android's output clock paces this loop and
 *    a momentarily slow consumer throttles PipeWire upstream instead of
 *    letting us buffer without bound. Backpressure is the clock.
 *
 * Capture (`audio-in-0`, `AudioRecord`, gated on `RECORD_AUDIO`) is a
 * separate milestone and deliberately not here.
 */
class TawcAudioBridge(private val shareDir: File) {

    companion object {
        private const val TAG = "tawc"

        /** Guest-visible name; the same inode is `<shareDir>/audio-out-0`. */
        const val ENDPOINT_NAME = "audio-out-0"

        const val SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_SAMPLE = 2

        /** Read granularity. Only affects syscall count, not latency. */
        private const val CHUNK_BYTES = 4096

        /** Multiplier on getMinBufferSize for the track buffer. */
        private const val BUFFER_MULTIPLE = 4

        /** Idle backoff when no writer is attached to the FIFO. */
        private const val IDLE_MS = 50L

        /** Backoff after tearing down a failed track/endpoint. */
        private const val RETRY_MS = 200L

        /**
         * Process-wide guard. Two bridges reading one FIFO don't
         * duplicate the audio -- the kernel hands each chunk to exactly
         * one reader, so every sample would be split across two tracks
         * and both would sound wrong. That is a silent corruption rather
         * than a loud failure, so it is worth making structurally
         * impossible instead of relying on every call site being right.
         */
        @Volatile
        private var active: TawcAudioBridge? = null

        private val lock = Any()
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    val endpoint: File get() = File(shareDir, ENDPOINT_NAME)

    /** Idempotent, and a no-op if another bridge already owns the FIFO. */
    fun start() {
        synchronized(lock) {
            if (active != null) {
                Log.w(TAG, "audio out: bridge already running; ignoring second start")
                return
            }
            if (!running.compareAndSet(false, true)) return
            if (!createEndpoint()) {
                running.set(false)
                return
            }
            active = this
        }
        worker = Thread({ pump() }, "tawc-audio-out").apply {
            // Daemon: an Application-scoped thread has no lifecycle to
            // join, and the process can be torn down at any time.
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        synchronized(lock) {
            if (active === this) active = null
        }
        worker?.interrupt()
        worker = null
        // The guest may still be running and holding the old inode; it will
        // see the endpoint vanish and recreate it on next session. Removing
        // it here is what keeps a killed session from leaving a FIFO that
        // nothing ever drains.
        if (!endpoint.delete()) {
            Log.w(TAG, "audio out: could not remove $ENDPOINT_NAME")
        }
    }

    /**
     * Create the FIFO, or accept an existing one. A non-FIFO squatting on
     * the name is left alone rather than deleted: we don't know who put it
     * there, and the guest is better served by a loud failure than by us
     * removing a file we didn't create.
     */
    private fun createEndpoint(): Boolean {
        val f = endpoint
        if (f.exists()) {
            val st = try {
                Os.stat(f.absolutePath)
            } catch (e: ErrnoException) {
                null
            }
            if (st != null && OsConstants.S_ISFIFO(st.st_mode)) return true
            Log.w(TAG, "audio out: $ENDPOINT_NAME exists and is not a FIFO; not touching it")
            return false
        }
        return try {
            // 0666: the guest is this same uid, but the rootfs view is
            // rebuilt per install and may differ in mode bits.
            Os.mkfifo(f.absolutePath, 0b110_110_110)
            Log.i(TAG, "audio out: created ${f.absolutePath}")
            true
        } catch (e: ErrnoException) {
            Log.w(TAG, "audio out: mkfifo ${f.absolutePath} failed: ${e.message}")
            false
        }
    }

    private fun pump() {
        var track: AudioTrack? = null
        var src: RandomAccessFile? = null
        val chunk = ByteArray(CHUNK_BYTES)
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (running.get()) {
                try {
                    if (src == null) src = RandomAccessFile(endpoint, "rw")
                    val n = src.read(chunk)
                    if (n <= 0) {
                        // Nobody is playing. O_RDWR means this is a real
                        // "no writer", not a disconnect, so there is
                        // nothing to recover from -- just don't spin.
                        Thread.sleep(IDLE_MS)
                        continue
                    }
                    // Created on first real audio, not up front: a track
                    // that has been play()ed with nothing written shows up
                    // in audio_flinger as an active output that is
                    // silently underrunning, which is a lie we shouldn't
                    // tell while the app is idle.
                    if (track == null) track = newTrack()
                    var off = 0
                    while (off < n && running.get()) {
                        val w = track.write(chunk, off, n - off)
                        if (w < 0) error("AudioTrack.write returned $w")
                        off += w
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    if (!running.get()) break
                    // A dead AudioTrack (route change, audio focus loss
                    // elsewhere) or a vanished endpoint. Drop both and
                    // rebuild; the sleep keeps a persistent failure from
                    // becoming a log flood.
                    Log.w(TAG, "audio out: rebuilding after ${e.javaClass.simpleName}: ${e.message}")
                    closeQuietly(src)
                    src = null
                    releaseQuietly(track)
                    track = null
                    Thread.sleep(RETRY_MS)
                }
            }
        } catch (e: InterruptedException) {
            // stop()
        } finally {
            closeQuietly(src)
            releaseQuietly(track)
        }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) error("AudioTrack.getMinBufferSize returned $min")
        val bytes = min * BUFFER_MULTIPLE
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            error("AudioTrack not initialized (state=${t.state})")
        }
        t.play()
        Log.i(TAG, "audio out: track up, buffer=$bytes bytes (min=$min)")
        return t
    }

    private fun closeQuietly(c: RandomAccessFile?) {
        try {
            c?.close()
        } catch (e: Exception) {
            Log.w(TAG, "audio out: close failed: ${e.message}")
        }
    }

    private fun releaseQuietly(t: AudioTrack?) {
        try {
            t?.pause()
            t?.flush()
            t?.release()
        } catch (e: Exception) {
            Log.w(TAG, "audio out: release failed: ${e.message}")
        }
    }
}
