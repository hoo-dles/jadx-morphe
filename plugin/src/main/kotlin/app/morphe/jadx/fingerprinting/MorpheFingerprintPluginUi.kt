package app.morphe.jadx.fingerprinting

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.analysis.reflection.util.ReflectionUtils
import com.formdev.flatlaf.extras.FlatSVGIcon
import io.github.oshai.kotlinlogging.KotlinLogging
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.gui.JadxGuiContext
import jadx.gui.ui.MainWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.*
import java.lang.reflect.Field
import javax.swing.*
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

const val MORPHE_ICON = "icons/morphe.svg"
const val PLAY_ARROW = "icons/play-arrow.svg"

object MorpheFingerprintPluginUi {
    private const val FRAME_NAME = "Morphe Fingerprint Evaluator"
    private const val MINIMAL_SETS_FRAME_NAME = "Fingerprinting Results"

    private val LOG = KotlinLogging.logger("${MorpheFingerprintPlugin.ID}/ui")
    private lateinit var context: JadxPluginContext
    private lateinit var guiContext: JadxGuiContext

    var fingerprintEvalFrame: JFrame? = null

    fun init(context: JadxPluginContext) {
        this.context = context
        this.guiContext = context.guiContext!!

        SwingUtilities.invokeLater {
            try {
                //Remove all frames with the title "Morphe Script Evaluator"
                JFrame.getFrames().filter { it.title == FRAME_NAME }.forEach { it.dispose() }
                JFrame.getFrames().filter { it.title == MINIMAL_SETS_FRAME_NAME }.forEach { it.dispose() }
                addToolbarButton()
            } catch (e: Exception) {
                LOG.error(e) { "Failed to initialize UI" }
                JOptionPane.showMessageDialog(
                    guiContext.mainFrame,
                    "Failed to initialize Morphe Fingerprint Plugin UI: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    private fun addToolbarButton() {
        try {
            val mainFrame = guiContext.mainFrame ?: run {
                LOG.warn { "Could not get main frame" }
                return
            }
            val mainPanel = getMainPanelReflectively(mainFrame) ?: run {
                LOG.warn { "Could not get main panel via reflection" }
                return
            }

            // Find the toolbar (assuming it's the component at index 2 in mainPanel's NORTH region)
            // This is fragile and depends on JADX internal layout
            var northPanel = mainPanel.components.find { comp ->
                mainPanel.layout is BorderLayout && (mainPanel.layout as BorderLayout).getConstraints(comp) == BorderLayout.NORTH
            }

            if (northPanel !is JToolBar) {
                // Fallback: Try the example's direct index approach if BorderLayout failed
                if (mainPanel.componentCount > 2 && mainPanel.getComponent(2) is JToolBar) {
                    northPanel = mainPanel.getComponent(2) as JToolBar
                } else {
                    LOG.warn { "Could not find JToolBar in main panel's NORTH region or at index 2. Found: ${northPanel?.javaClass?.name}" }
                    return
                }
            }

            val toolbar = northPanel
            val scriptButtonName = "${MorpheFingerprintPlugin.ID}.button"
            // Re-initialize the plugin button since if not there are classpath shenanigans
            toolbar.components.find { it.name == scriptButtonName }?.let {
                LOG.info { "Removing existing button from toolbar." }
                toolbar.remove(it)
            }

            val icon = inlineSvgIcon(MORPHE_ICON)
            val button = JButton(null, icon)
            button.name = scriptButtonName
            button.toolTipText = "Open Morphe Fingerprint Evaluator"

            button.addActionListener {
                LOG.debug { "Toolbar button clicked, showing UI." }
                if (fingerprintEvalFrame != null) {
                    fingerprintEvalFrame?.requestFocus()
                } else {
                    showScriptPanel()
                }
            }

            val preferencesIndex = toolbar.components.indexOfFirst { it.name?.contains("preferences") == true }
                .let { if (it == -1) toolbar.componentCount - 2 else it + 2 }
            toolbar.add(button, preferencesIndex) // Add after preferences button
            toolbar.revalidate()
            toolbar.repaint()
            LOG.info { "Added fingerprint evaluator button to toolbar." }

        } catch (e: Exception) {
            LOG.error(e) { "Failed to add button to toolbar" }
        }
    }

    // Helper function using reflection (similar to the Java example)
    private fun getMainPanelReflectively(frame: JFrame): JPanel? {
        return try {
            val field: Field = frame::class.java.getDeclaredField("mainPanel")
            field.isAccessible = true
            field.get(frame) as? JPanel
        } catch (e: Exception) {
            LOG.error(e) { "Failed to get mainPanel field via reflection" }
            null
        }
    }

    private fun showScriptPanel() {
        SwingUtilities.invokeLater {
            val frame = JFrame(FRAME_NAME)
            fingerprintEvalFrame = frame
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosing(e: java.awt.event.WindowEvent?) {
                    fingerprintEvalFrame = null
                }
            })
            frame.setSize(800, 600)
            frame.setLocationRelativeTo(guiContext.mainFrame) // Center relative to main frame
            frame.iconImage = inlineSvgIcon(MORPHE_ICON).image

            // Main panel with BorderLayout contains the CodePanel in the CENTER region.
            val mainPanel = JPanel(BorderLayout())
            mainPanel.border = BorderFactory.createEmptyBorder(10, 0, 0, 0) // Add padding
            val codePanel = CodePanel().apply {
                preferredSize = Dimension(600, 400)
            }
            (guiContext.mainFrame as MainWindow).editorThemeManager.apply(codePanel.codeArea)

            mainPanel.add(codePanel, BorderLayout.WEST)

            val resultPanel = JPanel(BorderLayout())

            val resultHeaderPanel = JPanel(GridLayout(2, 1, 10, 10))
            resultHeaderPanel.border = BorderFactory.createEmptyBorder(0, 10, 10, 0) // Add padding

            val upPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            val downPanel = JPanel(FlowLayout(FlowLayout.LEFT))

            fun defaultButton(tooltipText: String, icon: Icon): JButton {
                return JButton(null, icon).apply {
                    toolTipText = tooltipText
                    margin = Insets(3, 3, 3, 3)
                    preferredSize = Dimension(icon.iconWidth, icon.iconHeight)
                    maximumSize = preferredSize
                    border = BorderFactory.createEmptyBorder(3, 3, 3, 3)
                }
            }

            val runButton = defaultButton("Run the script", inlineSvgIcon(PLAY_ARROW) as Icon)
            val resultLabel = JLabel("Fingerprint result")
            resultLabel.border = BorderFactory.createEmptyBorder(0, 10, 0, 0) // Add padding

            upPanel.add(runButton)
            upPanel.add(resultLabel)

            resultHeaderPanel.add(upPanel)
            resultHeaderPanel.add(downPanel)
            resultPanel.add(resultHeaderPanel, BorderLayout.NORTH)

            val resultContentPanel = JPanel()
            resultContentPanel.layout = BorderLayout()

            val resultContentBox = Box.createVerticalBox()
            resultContentPanel.add(resultContentBox, BorderLayout.PAGE_START)

            val resultScrollPane = JScrollPane(resultContentPanel)
            resultPanel.add(resultScrollPane, BorderLayout.CENTER)
            mainPanel.add(resultPanel, BorderLayout.CENTER)

            fun onButtonClick(statusText: String) {
                runButton.isEnabled = false
                resultContentBox.removeAll()
                val statusLabel = JLabel(statusText)
                statusLabel.alignmentX = Component.LEFT_ALIGNMENT
                resultContentBox.add(statusLabel)
                resultContentBox.revalidate()
                resultContentBox.repaint()
                val script = codePanel.getText()
                GlobalScope.launch(Dispatchers.IO) {
                    LOG.debug { "Evaluating script: $script" }
                    var result: ResultWithDiagnostics<EvaluationResult>? = null
                    var evaluationError: Throwable? = null
                    val executionTime = measureTime {
                        try {
                            result = ScriptEvaluation.rawEvaluate(script)
                        } catch (t: Throwable) {
                            evaluationError = t
                            LOG.error(t) { "Exception during script evaluation" }
                        }
                    }

                    // Prepare result components (JLabels) in the background
                    val resultComponents = mutableListOf<Component>()
                    val outputBuilder = StringBuilder() // For logging or alternative display

                    if (evaluationError != null) {
                        val errorMsg = "Evaluation failed: ${evaluationError.message}"
                        resultComponents.add(createWrappedTextArea(errorMsg))
                        outputBuilder.appendLine(errorMsg)
                        // Optionally add stack trace details
                    } else {
                        when (val evalResult = result!!) {
                            is ResultWithDiagnostics.Failure -> {
                                val failMsg = "Script evaluation failed:"
                                resultComponents.add(createWrappedTextArea(failMsg))
                                outputBuilder.appendLine(failMsg)
                                ScriptEvaluation.LOG.error { failMsg }
                                evalResult.reports.forEach { report ->
                                    val message = "  ${report.severity}: ${report.message}"
                                    resultComponents.add(createWrappedTextArea(message))
                                    outputBuilder.appendLine(message)
                                    ScriptEvaluation.LOG.error { message }
                                    report.exception?.let {
                                        ScriptEvaluation.LOG.error(it) { "  Exception details:" }
                                        // Optionally add exception details to outputBuilder/components
                                    }
                                }
                            }

                            is ResultWithDiagnostics.Success -> {
                                when (val returnValue = evalResult.value.returnValue) {
                                    ResultValue.NotEvaluated -> {
                                        val msg = "Script was not evaluated."
                                        resultComponents.add(createWrappedTextArea(msg))
                                        outputBuilder.appendLine(msg)
                                    }

                                    is ResultValue.Error -> {
                                        val msg = "Script execution error: ${returnValue.error} "
                                        resultComponents.add(createWrappedTextArea(msg))
                                        outputBuilder.appendLine(msg)
                                        ScriptEvaluation.LOG.error(returnValue.error) { "Script execution error:" }
                                        // Optionally add stack trace
                                    }

                                    is ResultValue.Unit -> {
                                        val msg =
                                            "Script did not produce a value. Result type: ${returnValue::class.simpleName}"
                                        resultComponents.add(createWrappedTextArea(msg))
                                        outputBuilder.appendLine(msg)
                                        ScriptEvaluation.LOG.warn { msg }
                                    }

                                    is ResultValue.Value -> {
                                        val actualValue = returnValue.value
                                        if (actualValue == null) {
                                            val msg = "Script returned null."
                                            resultComponents.add(createWrappedTextArea(msg))
                                            outputBuilder.appendLine(msg)
                                            ScriptEvaluation.LOG.warn { msg }
                                        } else if (actualValue !is Fingerprint) {
                                            val msg = "Script returned unexpected type: ${returnValue.type}"
                                            resultComponents.add(createWrappedTextArea(msg))
                                            outputBuilder.appendLine(msg)
                                            ScriptEvaluation.LOG.error { msg }
                                            ScriptEvaluation.LOG.error { "Actual value classloader: ${actualValue.javaClass.classLoader}" }
                                            ScriptEvaluation.LOG.error { "Expected Fingerprint classloader: ${Fingerprint::class.java.classLoader}" }
                                        } else {
                                            ScriptEvaluation.LOG.debug { "Fingerprint: $actualValue" }
                                            outputBuilder.appendLine("Fingerprint: $actualValue")

                                            val searchResult = MorpheResolver.searchFingerprint(actualValue)
                                            ScriptEvaluation.LOG.info { "Search result $searchResult" }
                                            if (searchResult != null) {
//                                                matchedMethods.add(searchResult)
                                                outputBuilder.appendLine("Fingerprint found in APK: ${searchResult.definingClass}")
                                                outputBuilder.appendLine(
                                                    "originalFullName: ${
                                                        ReflectionUtils.dexToJavaName(
                                                            searchResult.definingClass
                                                        ).replace("$", ".")
                                                    }"
                                                )
                                                outputBuilder.appendLine("shortId: ${searchResult.getShortId()}")
                                                val javaKlass = context.decompiler.searchJavaClassByOrigFullName(
                                                    ReflectionUtils.dexToJavaName(
                                                        searchResult.definingClass
                                                    ).replace(
                                                        "$",
                                                        "."
                                                    )
                                                )
                                                outputBuilder.appendLine("javaKlass: $javaKlass")
                                                val fgMethod =
                                                    javaKlass?.searchMethodByShortId(searchResult.getShortId())
                                                outputBuilder.appendLine("fgMethod: $fgMethod")

                                                fgMethod?.let { sourceMethod ->
                                                    val searchResultMsg =
                                                        "Fingerprint found at method: ${sourceMethod.fullName}"
                                                    outputBuilder.appendLine(searchResultMsg)

                                                    resultComponents.add(createWrappedTextArea(searchResultMsg))
                                                    ScriptEvaluation.LOG.info { searchResultMsg }
                                                    val jumpButton = JButton("Jump to method")
                                                    jumpButton.addActionListener {
                                                        ScriptEvaluation.LOG.debug { "Jumping to method: ${sourceMethod.fullName}" }
                                                        val success = guiContext.open(sourceMethod.codeNodeRef)
                                                        if (success) {
                                                            ScriptEvaluation.LOG.debug { "Jumped to method: ${sourceMethod.fullName}" }
                                                        } else {
                                                            ScriptEvaluation.LOG.error { "Failed to jump to method: ${sourceMethod.fullName}" }
                                                            resultComponents.add(
                                                                createWrappedTextArea("Failed to jump to method do it manually or something: ${sourceMethod.fullName}")
                                                            )
                                                        }
                                                    }
                                                    resultComponents.add(jumpButton)
                                                }
                                            } else {
                                                val msg = "Fingerprint not found in the APK."
                                                resultComponents.add(createWrappedTextArea(msg))
                                                outputBuilder.appendLine(msg)
                                                ScriptEvaluation.LOG.warn { msg }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Switch back to the Event Dispatch Thread (EDT) to update the UI
                    withContext(Dispatchers.Swing) {
                        resultContentBox.removeAll() // Remove "Evaluating..." label
                        if (resultComponents.isEmpty()) {
                            resultContentBox.add(createWrappedTextArea(("No output.")))
                        } else {
                            resultComponents.forEach {
                                resultContentBox.add(
                                    it
                                )
                            }
                        }
                        ScriptEvaluation.LOG.info { "Script evaluation output:\n $outputBuilder" }

                        resultLabel.text = "Executed in ${executionTime.inWholeMilliseconds.milliseconds}"
                        runButton.isEnabled = true
                        resultContentBox.revalidate()
                        resultContentBox.repaint()
                        // Scroll to top if needed
                        resultScrollPane.verticalScrollBar.value = resultScrollPane.verticalScrollBar.minimum
                        LOG.debug { "Script evaluation UI updated." }
                    }
                }
            }

            runButton.addActionListener {
                onButtonClick("Evaluating...")
            }

            frame.contentPane = mainPanel
            frame.isVisible = true
        }
    }

    private fun createWrappedTextArea(text: String): JTextArea {
        val textArea = JTextArea(text)
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.isEditable = false // Make read-only
        textArea.alignmentX = Component.LEFT_ALIGNMENT // Align left
        textArea.alignmentY = Component.TOP_ALIGNMENT // Align top
        textArea.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2)) // Add padding
        return textArea
    }

    private fun inlineSvgIcon(path: String): FlatSVGIcon {
        val stream = this::class.java.classLoader.getResourceAsStream(path)
        return FlatSVGIcon(stream)
    }
}