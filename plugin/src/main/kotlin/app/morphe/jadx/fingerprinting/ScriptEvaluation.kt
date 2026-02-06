package app.morphe.jadx.fingerprinting

import app.morphe.jadx.fingerprinting.runtime.FingerprintScript
import app.morphe.jadx.fingerprinting.runtime.FingerprintScriptCompilationConfiguration
import app.morphe.patcher.Fingerprint
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object ScriptEvaluation {
    val LOG = KotlinLogging.logger("${MorpheFingerprintPlugin.ID}/script-eval")
    private val fingerprintScriptEvaluationConfiguration = ScriptEvaluationConfiguration {
        jvm {
            // Use the classloader of the FingerprintScript class, which should be the plugin's classloader
            baseClassLoader(FingerprintScript::class.java.classLoader)
        }
        // Add other evaluation configurations if needed
    }
    private val scriptingHost = BasicJvmScriptingHost(
        baseHostConfiguration = ScriptingHostConfiguration {
            jvm {
                baseClassLoader(FingerprintScript::class.java.classLoader)
            }
        },
    )

    init {
        LOG.info { "Preloading BasicJvmScriptingHost..." }
        val execTime = measureTime {
            // This is a no-op, but it forces the BasicJvmScriptingHost to initialize
            rawEvaluate("")
        }
        LOG.info { "Preloading done in ${execTime.inWholeMilliseconds.milliseconds}" }
    }

    fun rawEvaluate(string: String): ResultWithDiagnostics<EvaluationResult> {
        return scriptingHost.eval(
            string.toScriptSource(), FingerprintScriptCompilationConfiguration, fingerprintScriptEvaluationConfiguration
        )
    }

    fun evaluateFingerprintString(fingerprintString: String): Fingerprint? {
        LOG.debug { "***** Beginning evalFingerprint *****" }
        var returnedFingerprint: Fingerprint? = null
        val execTime = measureTime {
            LOG.debug { "Evaluating script: \n$fingerprintString" }
            val result = rawEvaluate(fingerprintString)
            returnedFingerprint = processEvalResult(result)
            LOG.debug { "Returned Fingerprint: $returnedFingerprint" }
        }
        LOG.debug { "***** evalFingerprint took ${execTime.inWholeMilliseconds.milliseconds} *****\n\n" }
        return returnedFingerprint
    }

    private fun processEvalResult(result: ResultWithDiagnostics<EvaluationResult>): Fingerprint? {
        if (result !is ResultWithDiagnostics.Success) {
            LOG.error { "Script evaluation failed:" }
            result.reports.forEach { report ->
                LOG.error { "  ${report.severity}: ${report.message}" }
                report.exception?.let {
                    LOG.error(it) { "  Exception during script evaluation:" }
                }
            }
            return null
        }

        val returnValue = result.value.returnValue
        if (returnValue !is ResultValue.Value) {
            LOG.warn { "Script did not produce a value result. Result type: ${returnValue::class.simpleName}" }
            return null
        }

        val actualValue = returnValue.value
        if (actualValue == null) {
            LOG.warn { "Script returned null." }
            return null
        }

        // The type name check might be fragile. It compares the expected type name string.
        if (actualValue !is Fingerprint) {
            LOG.error { "Script returned unexpected type: ${returnValue.type}" }
            LOG.error { "Actual value classloader: ${actualValue.javaClass.classLoader}" }
            LOG.error { "Expected Fingerprint classloader: ${Fingerprint::class.java.classLoader}" }
            return null
        }

        return actualValue
    }
}



