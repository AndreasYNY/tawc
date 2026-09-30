package me.phie.tawc.install

import android.content.Context
import java.io.File

/**
 * Ships the guest half of the audio stack into every rootfs:
 * [AudioDefaults.PIPEWIRE_CONF_PATH] (the output sink) and
 * [AudioDefaults.START_SCRIPT_PATH] (the daemon starter).
 *
 * Method-independent: both land in distro- or app-owned paths, so they're
 * copied under every method. Refreshed on app upgrade like every other
 * provider — which is the point, since a config change has to reach
 * installs that already exist.
 *
 * The packages these depend on are *not* handled here (a TawcInstall is a
 * file, not a package); they come from `Distro.optionalPackages`.
 */
internal object AudioInstallProvider : TawcInstallProvider {
    override val name: String = "audio"

    override fun entries(context: Context, methodKey: String): List<TawcInstall> {
        val dir = File(context.filesDir, "audio")
        dir.mkdirs()

        val conf = File(dir, "50-tawc-audio.conf")
        conf.writeText(AudioDefaults.PIPEWIRE_CONF_CONTENT)

        val starter = File(dir, "tawc-audio-start")
        starter.writeText(AudioDefaults.START_SCRIPT_CONTENT)
        // TawcInstaller.applyToRootfs propagates the source's exec bit
        // (the only way a COPY entry ends up executable), and providers
        // that ship binaries get it from the asset extract. We're
        // generating this file, so set it here.
        starter.setExecutable(true, false)

        return listOf(
            TawcInstall(
                src = conf.absolutePath,
                dest = AudioDefaults.PIPEWIRE_CONF_PATH,
                type = TawcInstall.Type.COPY,
            ),
            TawcInstall(
                src = starter.absolutePath,
                dest = AudioDefaults.START_SCRIPT_PATH,
                type = TawcInstall.Type.COPY,
            ),
        )
    }
}
