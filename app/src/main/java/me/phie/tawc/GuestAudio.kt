package me.phie.tawc

import android.content.Context
import android.util.Log
import me.phie.tawc.audio.TawcAudioBridge
import me.phie.tawc.install.AudioDefaults
import me.phie.tawc.install.InstallationMethod
import me.phie.tawc.install.InstallationStore
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Brings the guest audio stack up, per install.
 *
 * The rootfs has no init, so nothing else starts `pipewire` /
 * `wireplumber`. This runs the starter script
 * ([AudioDefaults.START_SCRIPT_PATH], shipped by `AudioInstallProvider`)
 * once per install; the script is idempotent and cheap, so calling it on
 * every app start is correct and self-healing after a guest crash.
 *
 * Called from [TawcApplication]'s startup thread, **after**
 * [TawcAudioBridge.start]. Order matters: the bridge `mkfifo`s the
 * endpoint synchronously, and `pipe-tunnel` opens that path at module
 * init — if the daemon wins the race it fails to open the FIFO and the
 * sink is broken for the life of the process.
 *
 * Deliberately uses [InstallationMethod.startInside] rather than
 * `UserRootfsSession.startInside`: that helper ties a [me.phie.tawc.
 * session.SessionHolds] reference to the spawned process's exit, but the
 * script exits immediately while the daemons it starts live on. The
 * daemons are picked up as strays by the normal session tail-state scan
 * (their `exe` is inside the rootfs), which is how this project already
 * treats a daemon a guest left behind.
 */
object GuestAudio {
    private const val TAG = "tawc"

    /**
     * Fire the starter for every installation. Never throws: a distro
     * without the audio packages, a rootless method that can't spawn, or
     * a half-installed slot must not stop the app from starting.
     */
    fun start(context: Context) {
        val store = InstallationStore(context)
        for (inst in store.list()) {
            try {
                startOne(context, store, inst.id, inst.method)
            } catch (t: Throwable) {
                Log.w(TAG, "audio: start failed for ${inst.id}: $t")
            }
        }
    }

    private fun startOne(
        context: Context,
        store: InstallationStore,
        id: String,
        methodKey: String,
    ) {
        val method = InstallationMethod.forKey(context, methodKey) ?: run {
            Log.w(TAG, "audio: no install method for '$methodKey', skipping $id")
            return
        }
        val rootfs = store.rootfsDir(id)
        if (!rootfs.isDirectory) {
            Log.w(TAG, "audio: no rootfs at $rootfs, skipping $id")
            return
        }
        val script = rootfs.resolve(AudioDefaults.START_SCRIPT_PATH.removePrefix("/"))
        if (!script.exists()) {
            // Normal before the first TawcInstaller pass over this rootfs
            // (or on an install that predates audio support).
            Log.w(TAG, "audio: ${AudioDefaults.START_SCRIPT_PATH} missing in $id, skipping")
            return
        }

        val proc = method.startInside(rootfs.absolutePath, AudioDefaults.START_SCRIPT_PATH)
        // Drain both pipes. The script's own output is small, but leaving
        // them unread risks filling the 64 KB buffer and wedging the spawn.
        drain(proc.inputStream, "stdout")
        drain(proc.errorStream, "stderr")
        thread(name = "tawc-audio-start", isDaemon = true) {
            try {
                val rc = proc.waitFor()
                // NOT an error signal. The script exits 0 when run
                // directly (verified), but the daemons it starts detach,
                // and the spawn wrapper is `exec setsid` -- so what we
                // wait on isn't reliably the script's own exit. Observed
                // rc=137 (SIGKILL, consistent with the phantom-process
                // killer in issues/) with the daemons up and audio
                // working. Retry is automatic: the next app start runs
                // the idempotent script again, and the guest-side
                // /tmp/tawc-audio.log has the real story.
                if (rc != 0) {
                    Log.i(TAG, "audio: starter for $id reported $rc (see guest /tmp/tawc-audio.log)")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun drain(stream: InputStream, what: String) {
        thread(name = "tawc-audio-$what", isDaemon = true) {
            try {
                stream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) Log.i(TAG, "audio[$what]: $line")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "audio: $what drain ended: ${e.message}")
            }
        }
    }
}
