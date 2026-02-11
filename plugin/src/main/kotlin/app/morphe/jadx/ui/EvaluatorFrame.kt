package app.morphe.jadx.ui

import app.morphe.jadx.Log
import app.morphe.jadx.PluginOptions
import app.morphe.jadx.eval.MorpheResolver
import app.morphe.jadx.eval.ScriptingHost
import app.morphe.jadx.eval.getShortId
import app.morphe.jadx.ui.components.IconButton
import app.morphe.jadx.ui.components.TextArea
import app.morphe.jadx.ui.components.codepanel.CodePanel
import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.analysis.reflection.util.ReflectionUtils
import com.android.tools.smali.dexlib2.iface.Method
import jadx.api.plugins.JadxPluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.*
import javax.swing.*
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics

private const val SEARCH_TEXT = "Evaluate Fingerprint"

class EvaluatorFrame(private val context: JadxPluginContext, options: PluginOptions) : JFrame(NAME) {
    companion object {
        const val NAME = "Morphe Fingerprint Evaluator"
    }

    private val guiContext = context.guiContext!!
    private val codePanel: CodePanel
    private val resultLabel: JLabel
    private val runButton: JButton
    private val resultContentPanel: JPanel
    private val resultScrollPane: JScrollPane

    init {
        // Main frame and content panel
        setSize(800, 500)
        minimumSize = Dimension(400, 200)
        setLocationRelativeTo(guiContext.mainFrame)
        iconImage = loadSvg(MORPHE_ICON_PATH).image
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.dividerLocation = 550
        splitPane.resizeWeight = 1.0
        splitPane.border = BorderFactory.createEmptyBorder(10, 4, 4, 4)

        // Code panel
        codePanel = CodePanel(guiContext, options) { onSearch() }
        splitPane.leftComponent = codePanel
        splitPane.leftComponent.minimumSize = Dimension(300, 200)

        // Right panel for actions results
        val rightPanel = JPanel(BorderLayout())

        // Upper section of right panel for run button and label
        val resultHeaderPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        resultHeaderPanel.border = BorderFactory.createEmptyBorder(0, 10, 10, 0)
        runButton = IconButton(loadSvg(PLAY_ARROW_PATH), "Run (Ctrl+Enter)")
        runButton.addActionListener { onSearch() }
        resultHeaderPanel.add(runButton)
        resultLabel = JLabel(SEARCH_TEXT)
        resultLabel.border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
        resultHeaderPanel.add(resultLabel)
        rightPanel.add(resultHeaderPanel, BorderLayout.NORTH)

        // Evaluation result section
        resultContentPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        resultScrollPane = JScrollPane(resultContentPanel)
        rightPanel.add(resultScrollPane)

        splitPane.rightComponent = rightPanel
        splitPane.rightComponent.minimumSize = Dimension(200, 200)
        contentPane = splitPane
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)

        if (!visible) {
            codePanel.reset()
            codePanel.requestFocus()
            resultLabel.text = SEARCH_TEXT
            resultContentPanel.removeAll()
        }
    }

    private fun onSearch() {
        runButton.isEnabled = false
        resultLabel.text = "Searching..."
        resultContentPanel.clearAndRepaint()

        GlobalScope.launch(Dispatchers.IO) {
            val resultComponent = try {
                val evalResult = ScriptingHost.evaluate(codePanel.text)
                resultAsFingerprint(evalResult)?.let {
                    val method = MorpheResolver.matchMethod(it)
                    generateFingerprintComponent(method)
                } ?: run {
                    generateNonFingerprintComponent(evalResult)
                }
            } catch (t: Throwable) {
                Log.error(t) { "Exception while evaluation and matching fingerprint" }
                TextArea("Evaluation failed:\n    ${t.message}")
            }

            // Switch back to the Event Dispatch Thread (EDT) to update the UI
            withContext(Dispatchers.Swing) {
                resultContentPanel.add(resultComponent)
                resultLabel.text = SEARCH_TEXT
                runButton.isEnabled = true
                // Scroll to top
                resultScrollPane.verticalScrollBar.value = resultScrollPane.verticalScrollBar.minimum
            }
        }
    }

    private fun resultAsFingerprint(result: ResultWithDiagnostics<EvaluationResult>) =
        ((result as? ResultWithDiagnostics.Success)
            ?.value
            ?.returnValue as? ResultValue.Value)
            ?.value as? Fingerprint

    private fun generateNonFingerprintComponent(result: ResultWithDiagnostics<EvaluationResult>): Component {
        val text = when (result) {
            is ResultWithDiagnostics.Failure ->
                (listOf("Script parsing failed:") + result.reports.map { "    ${it.severity}: ${it.message}" })
                    .joinToString("\n")
            is ResultWithDiagnostics.Success -> completedComponentText(result.value.returnValue)
        }
        return TextArea(text)
    }

    private fun completedComponentText(result: ResultValue) =
        when (result) {
            is ResultValue.Error -> "Script execution returned an error:\n    ${result.error.message}"
            is ResultValue.NotEvaluated -> "Script was not evaluated."
            is ResultValue.Unit -> "Script execution did not produce a value."
            is ResultValue.Value -> "Script execution returned unexpected type:\n    ${result.type}"
        }

    private fun generateFingerprintComponent(method: Method?): Component {
        if (method != null) {
            val javaKlass = context.decompiler.searchJavaClassByOrigFullName(
                ReflectionUtils.dexToJavaName(method.definingClass)
                    .replace("$",".")
            )
            val javaMethod = javaKlass?.searchMethodByShortId(method.getShortId())
            javaMethod?.let { jMethod ->
                val combined = JPanel()
                combined.layout = BoxLayout(combined, BoxLayout.Y_AXIS)
                combined.add(TextArea("Fingerprint found at method:\n    ${jMethod.fullName}"))

                val jumpButton = JButton("Jump to method")
                jumpButton.addActionListener {
                    if (!guiContext.open(jMethod.codeNodeRef)) {
                        Log.error { "Failed to jump to method: ${jMethod.fullName}" }
                    }
                }
                combined.add(jumpButton)
                return combined
            }
        }

        return TextArea("Fingerprint not found in the APK")
    }
}