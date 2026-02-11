package app.morphe.jadx.eval

import app.morphe.jadx.Log
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.Method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

object MorpheResolver {
    private lateinit var sourceApk: File
    private lateinit var temp: File

    fun init(
        sourceApk: File,
        temp: File,
    ) {
        this.sourceApk = sourceApk
        // Add a random suffix to the temporary files path
        this.temp = File(temp, UUID.randomUUID().toString())

        GlobalScope.launch(Dispatchers.IO) {
            ScriptingHost.preload()
        }
    }

    fun matchMethod(fingerprint: Fingerprint): Method? {
        var match: Method? = null
        val tempPatch = bytecodePatch(
            name = "Temporary patch for searching fingerprint"
        ) {
            execute {
                match = fingerprint.originalMethodOrNull
                if (match != null) Log.info { "Fingerprint matched method: ${match!!.getShortId()}" }
                else Log.warn { "Fingerprint did not match any method" }
            }
        }

        // New Patcher instance must be created on each evaluation
        val patcher = Patcher(
            PatcherConfig(
                this.sourceApk,
                this.temp,
                null,
                this.temp.absolutePath,
            ),
        )

        patcher.use {
            it += setOf(tempPatch)
            runBlocking {
                it().collect { result ->
                    result.exception?.let { ex ->
                        Log.error(ex) { "Application of temporary patch failed" }
                    }
                }
            }
        }

        return match
    }
}
