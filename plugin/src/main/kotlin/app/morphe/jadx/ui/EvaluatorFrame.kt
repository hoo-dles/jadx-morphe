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

private const val SEARCH_TEXT = "Evaluate"

class EvaluatorFrame(private val context: JadxPluginContext, options: PluginOptions) : JFrame(NAME) {
    companion object {
        const val NAME = "Morphe Fingerprint Evaluator"
    }

    private val guiContext = context.guiContext!!
    private val codePanel: CodePanel
    private val executeLabel: JLabel
    private val resultsLabel: JLabel
    private val runButton: JButton
    private val resultContentPanel: JPanel
    private val resultScrollPane: JScrollPane

    init {
        // Main frame and content panel
        setSize(900, 500)
        minimumSize = Dimension(600, 300)
        setLocationRelativeTo(guiContext.mainFrame)
        iconImage = loadSvg(MORPHE_ICON_PATH).image
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.dividerLocation = 550
        splitPane.resizeWeight = 0.5
        splitPane.border = BorderFactory.createEmptyBorder(10, 4, 4, 4)

        // Code panel
        codePanel = CodePanel(guiContext, options) { onSearch() }
        splitPane.leftComponent = codePanel
        splitPane.leftComponent.minimumSize = Dimension(250, 300)

        // Right panel for actions results
        val rightPanel = JPanel(BorderLayout())

        // Upper section of right panel for run button and label
        val resultHeaderPanel = JPanel()
        resultHeaderPanel.layout = BoxLayout(resultHeaderPanel, BoxLayout.X_AXIS)
        resultHeaderPanel.border = BorderFactory.createEmptyBorder(0, 10, 10, 10)

        runButton = IconButton(loadSvg(PLAY_ARROW_PATH), "Run (Ctrl+Enter)")
        runButton.addActionListener { onSearch() }
        resultHeaderPanel.add(runButton)
        executeLabel = JLabel(SEARCH_TEXT)
        executeLabel.border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
        resultHeaderPanel.add(executeLabel)

        resultHeaderPanel.add(Box.createHorizontalGlue());

        resultsLabel = JLabel()
        resultHeaderPanel.add(resultsLabel)

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
            executeLabel.text = SEARCH_TEXT
            resultContentPanel.removeAll()
        }
    }

    private fun onSearch() {
        runButton.isEnabled = false
        executeLabel.text = "Searching..."
        resultContentPanel.clearAndRepaint()

        GlobalScope.launch(Dispatchers.IO) {
            lateinit var component: Component
            var methodCount = 0
            try {
                val evalResult = ScriptingHost.evaluate(codePanel.text)
                resultAsFingerprint(evalResult)?.let {
                    val methods = MorpheResolver.matchMethods(it)
                    component = matchesComponent(methods)
                    methodCount = methods.size
                } ?: run {
                    component = messageComponent(evalResult)
                }
            } catch (t: Throwable) {
                Log.error(t) { "Exception while evaluation and matching fingerprint" }
                component = TextArea("Evaluation failed:\n    ${t.message}")
            }

            // Switch back to the Event Dispatch Thread (EDT) to update the UI
            withContext(Dispatchers.Swing) {
                resultContentPanel.add(component)
                resultsLabel.text = when (methodCount) {
                    0 -> ""
                    1 -> "Found 1 match"
                    else -> "Found $methodCount matches"
                }
                executeLabel.text = SEARCH_TEXT
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

    private fun messageComponent(result: ResultWithDiagnostics<EvaluationResult>): Component {
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

    private fun matchesComponent(methods: List<Method>): Component {
        if (methods.isNotEmpty()) {
            val resultsPanel = JPanel()
            resultsPanel.layout = BoxLayout(resultsPanel, BoxLayout.Y_AXIS)

            val matchBlocks = methods.map {
                val javaKlass = context.decompiler.searchJavaClassByOrigFullName(
                    ReflectionUtils.dexToJavaName(it.definingClass)
                        .replace("$",".")
                )
                val javaMethod = javaKlass?.searchMethodByShortId(it.getShortId())
                javaMethod?.let { jMethod ->
                    val block = JPanel()
                    block.layout = BoxLayout(block, BoxLayout.Y_AXIS)
                    block.add(TextArea(jMethod.fullName))

                    val jumpButton = JButton("Jump to method")
                    jumpButton.addActionListener {
                        if (!guiContext.open(jMethod.codeNodeRef)) {
                            Log.error { "Failed to jump to method: ${jMethod.fullName}" }
                        }
                    }
                    block.add(jumpButton)
                    block
                }
            }

            matchBlocks.forEachIndexed { index, block ->
                resultsPanel.add(block)
                if (index < matchBlocks.size - 1)
                    resultsPanel.add(Box.createVerticalStrut(15))
            }

            return resultsPanel
        }

        return TextArea("No matches found.")
    }
}