package app.morphe.jadx.fingerprinting

import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import jadx.api.plugins.JadxPluginInfoBuilder

class MorpheFingerprintPlugin : JadxPlugin {
    companion object {
        const val ID = "jadx-morphe"
        private val LOG = KotlinLogging.logger("$ID/plugin")
    }

    override fun getPluginInfo(): JadxPluginInfo {
        return JadxPluginInfoBuilder.pluginId(ID)
            .name("JADX Morphe")
            .description("Morphe fingerprint scripting for JADX")
            .homepage("https://github.com/hoo-dles/jadx-morphe")
            .requiredJadxVersion("1.5.2, r2472")
            .build()
    }

    override fun init(init: JadxPluginContext) {
        LOG.debug { init.args }
        LOG.debug { init.args.inputFiles }

        val sourceApk = init.args.inputFiles.firstOrNull()
        if (sourceApk == null || !sourceApk.exists()) {
            LOG.error { "No APK file found" }
            return
        }
        MorpheResolver.createPatcher(sourceApk, init.files().pluginTempDir.toFile())

        LOG.info { "Morphe fingerprint plugin is enabled" }
        init.guiContext?.let {
            MorpheFingerprintPluginUi.init(init)
        }
    }
}