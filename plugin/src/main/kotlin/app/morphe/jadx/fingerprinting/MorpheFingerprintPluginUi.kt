package app.morphe.jadx.fingerprinting

import app.morphe.jadx.fingerprinting.solver.Solver
import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.analysis.reflection.util.ReflectionUtils
import com.android.tools.smali.dexlib2.iface.Method
import com.formdev.flatlaf.extras.FlatSVGIcon
import io.github.oshai.kotlinlogging.KotlinLogging
import jadx.api.metadata.ICodeNodeRef
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.gui.JadxGuiContext
import jadx.core.dex.nodes.MethodNode
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

const val COPY_ICON = "icons/copy.svg"
const val MORPHE_ICON = "icons/morphe.svg"
const val NEXT_ICON = "icons/next-arrow.svg"
const val PLAY_ARROW = "icons/play-arrow.svg"
const val PREV_ARROW = "icons/prev-arrow.svg"

object MorpheFingerprintPluginUi {
    private const val FRAME_NAME = "Morphe Fingerprint Evaluator"
    private const val MINIMAL_SETS_FRAME_NAME = "Fingerprinting Results"

    private val LOG = KotlinLogging.logger("${MorpheFingerprintPlugin.ID}/ui")
    private var matchedMethods = linkedSetOf<Method>()
    private var navigationIndex = 0
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
                // TODO: Update Solver to generate Morphe-style fingerprints
                // addCopyFingerprintAction()
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

    fun isCopyFingerprintActionEnabled(codeNodeRef: ICodeNodeRef): Boolean {
        return codeNodeRef is MethodNode
    }

    fun copyFingerprintAction(codeNodeRef: ICodeNodeRef) {
        try {
            val methodNode = codeNodeRef as MethodNode
            val methodInfo = methodNode.methodInfo
            LOG.info { "Generating fingerprints for method: ${methodInfo.shortId}" }
            val uniqueMethodId = "${ReflectionUtils.javaToDexName(methodNode.parentClass.rawName)}${methodInfo.shortId}"
            try {
                val minimalSets = Solver.getMinimalDistinguishingFeatures(uniqueMethodId)
                if (minimalSets.isEmpty()) {
                    LOG.warn { "No feature sets found for method $uniqueMethodId" }
                    JOptionPane.showMessageDialog(
                        guiContext.mainFrame,
                        "Could not find any distinguishing feature sets for this method.",
                        "No Sets Found",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                // Show the window with all minimal sets
                showMinimalSetsWindow(minimalSets, methodNode)

            } catch (e: IllegalStateException) {
                LOG.error(e) { "Failed to find feature sets for $uniqueMethodId" }
                JOptionPane.showMessageDialog(
                    guiContext.mainFrame,
                    "Failed to generate fingerprints: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            } catch (e: Exception) {
                LOG.error(e) { "Failed during fingerprint generation or display for $uniqueMethodId" }
                JOptionPane.showMessageDialog(
                    guiContext.mainFrame,
                    "An unexpected error occurred: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        } catch (e: Exception) {
            LOG.error(e) { "Failed to process method node for fingerprinting" }
            JOptionPane.showMessageDialog(
                guiContext.mainFrame,
                "Failed to get method details: ${e.message}",
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun showMinimalSetsWindow(
        minimalSets: List<List<String>>, methodNode: MethodNode
    ) {
        val methodShortId = methodNode.methodInfo.shortId
        val uniqueMethodId = "${ReflectionUtils.javaToDexName(methodNode.parentClass.rawName)}${methodShortId}"
        val methodFeatures = Solver.getMethodFeatures(uniqueMethodId)
        val fullMethodFingerprint = Solver.featuresToFingerprintString(methodFeatures)
        SwingUtilities.invokeLater {
            // Close existing window if open
            JFrame.getFrames().find { it.title == MINIMAL_SETS_FRAME_NAME }?.dispose()

            val frame = JFrame(MINIMAL_SETS_FRAME_NAME)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.setSize(700, 500)
            frame.setLocationRelativeTo(guiContext.mainFrame)

            val mainPanel = JPanel(GridBagLayout()) // Changed layout
            mainPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

            val gbc = GridBagConstraints()
            gbc.gridx = 0 // All components in the first column
            gbc.gridy = GridBagConstraints.RELATIVE // Place components below each other
            gbc.weightx = 1.0 // Allow horizontal stretching
            gbc.fill = GridBagConstraints.HORIZONTAL // Fill available horizontal space
            gbc.anchor = GridBagConstraints.NORTHWEST // Anchor to top-left
            gbc.insets = Insets(0, 0, 0, 0) // Default spacing

            val titleLabel =
                JTextArea("Found ${minimalSets.size} fingerprint(s) for method : $uniqueMethodId")
            titleLabel.isEditable = false
            titleLabel.lineWrap = true
            titleLabel.wrapStyleWord = true
            titleLabel.preferredSize = Dimension(0, 50)

            gbc.insets = Insets(0, 0, 10, 0) // Add bottom margin
            mainPanel.add(titleLabel, gbc)

            val fullFingerprintPanel = JPanel(BorderLayout(5, 5))
            fullFingerprintPanel.border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Full Method Fingerprint | ${methodFeatures.size} feature(s) "),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
            )

            val fullTextArea = JTextArea(fullMethodFingerprint)
            fullTextArea.isEditable = false
            fullTextArea.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            fullTextArea.tabSize = 2

            val fullCopyButton = JButton(null, inlineSvgIcon(COPY_ICON))
            fullCopyButton.toolTipText = "Copy the full method fingerprint to clipboard"
            fullCopyButton.addActionListener {
                try {
                    guiContext.copyToClipboard(fullMethodFingerprint)
                    LOG.info { "Copied full method fingerprint to clipboard." }
                    fullCopyButton.isEnabled = false
                    Timer(1500) {
                        fullCopyButton.isEnabled = true
                    }.apply { isRepeats = false }.start()
                } catch (e: Exception) {
                    LOG.error(e) { "Failed to copy full fingerprint string to clipboard" }
                    JOptionPane.showMessageDialog(
                        frame,
                        "Failed to copy to clipboard: ${e.message}",
                        "Copy Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }

            fullFingerprintPanel.add(fullTextArea, BorderLayout.CENTER)
            fullFingerprintPanel.add(fullCopyButton, BorderLayout.EAST)

            gbc.insets = Insets(0, 0, 15, 0)
            mainPanel.add(fullFingerprintPanel, gbc)
            mainPanel.add(Box.createRigidArea(Dimension(0, 15)))

            minimalSets.forEachIndexed { index, featureSet ->
                val fingerprintString = try {
                    Solver.featuresToFingerprintString(featureSet)
                } catch (e: Exception) {
                    LOG.error(e) { "Failed to convert feature set to string: $featureSet" }
                    "Error generating fingerprint string: ${e.message}"
                }

                val setPanel = JPanel(BorderLayout(5, 5)) // Panel for each set
                setPanel.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("Fingerprint ${index + 1} | ${featureSet.size} feature(s) "),
                    BorderFactory.createEmptyBorder(5, 5, 5, 5)
                )

                val textArea = JTextArea(fingerprintString)
                textArea.isEditable = false
                textArea.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                textArea.tabSize = 2

                val copyButton = JButton(null, inlineSvgIcon(COPY_ICON))
                copyButton.toolTipText = "Copy this fingerprint to clipboard"
                copyButton.addActionListener {
                    try {
                        guiContext.copyToClipboard(fingerprintString)
                        LOG.info { "Copied fingerprint set ${index + 1} to clipboard." }
                        copyButton.isEnabled = false
                        Timer(1500) {
                            copyButton.isEnabled = true
                        }.apply { isRepeats = false }.start()

                    } catch (e: Exception) {
                        LOG.error(e) { "Failed to copy fingerprint string to clipboard" }
                        JOptionPane.showMessageDialog(
                            frame,
                            "Failed to copy to clipboard: ${e.message}",
                            "Copy Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }

                setPanel.add(textArea, BorderLayout.CENTER)
                setPanel.add(copyButton, BorderLayout.EAST)

                gbc.insets = Insets(0, 0, 10, 0)
                mainPanel.add(setPanel, gbc)
            }
            gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.VERTICAL
            mainPanel.add(Box.createVerticalGlue(), gbc)

            val containerPanel = JPanel(BorderLayout())
            containerPanel.add(mainPanel, BorderLayout.NORTH)

            val scrollPane = JScrollPane(containerPanel)
            scrollPane.verticalScrollBar.unitIncrement = 16

            frame.contentPane.add(scrollPane)
            frame.isVisible = true
        }
    }

    private fun addCopyFingerprintAction() {
        guiContext.addPopupMenuAction(
            "Generate Morphe fingerprint",
            ::isCopyFingerprintActionEnabled,
            "M",
            ::copyFingerprintAction
        )
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
            val nextButton = defaultButton("Search next", inlineSvgIcon(NEXT_ICON) as Icon).apply { isEnabled = false }
            val previousButton = defaultButton("Search previous", inlineSvgIcon(PREV_ARROW) as Icon).apply { isEnabled = false }

            val resultLabel = JLabel("Fingerprint result")
            resultLabel.border = BorderFactory.createEmptyBorder(0, 10, 0, 0) // Add padding

            upPanel.add(runButton)
            upPanel.add(resultLabel)

            downPanel.add(previousButton)
            downPanel.add(nextButton)
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
                nextButton.isEnabled = false
                previousButton.isEnabled = false
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
                        val errorMsg = "Evaluation failed: ${evaluationError!!.message}"
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
//                                            ScriptEvaluation.LOG.info { "Index: $navigationIndex" }
//                                            ScriptEvaluation.LOG.info { "Current set: $matchedMethods" }
                                            ScriptEvaluation.LOG.debug { "Fingerprint: $actualValue" }
                                            outputBuilder.appendLine("Fingerprint: $actualValue")

                                            val searchResult = if (navigationIndex in matchedMethods.indices) {
                                                ScriptEvaluation.LOG.info { "Index $navigationIndex found in map" }
                                                matchedMethods.elementAt(navigationIndex)
                                            } else {
                                                ScriptEvaluation.LOG.info { "Not found in map: searching" }
                                                MorpheResolver.searchFingerprint(actualValue)
                                            }
                                            ScriptEvaluation.LOG.info { "Search result $searchResult" }
                                            if (searchResult != null) {
                                                matchedMethods.add(searchResult)
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
                                                    ) // Make sure subclass $ is replaced with dot TODO: this might error if the class name has a $ but what can you do
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
                                                    nextButton.isEnabled = true
                                                }
                                            } else {
                                                val msg =
                                                    if (navigationIndex == 0) "Fingerprint not found in the APK." else "No more results found."
                                                resultComponents.add(createWrappedTextArea(msg))
                                                outputBuilder.appendLine(msg)
                                                ScriptEvaluation.LOG.warn { msg }
                                                nextButton.isEnabled = false
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
                        previousButton.isEnabled = navigationIndex > 0
                        // Ensure layout updates are processed
                        resultContentBox.revalidate()
                        resultContentBox.repaint()
                        // Scroll to top if needed
                        resultScrollPane.verticalScrollBar.value = resultScrollPane.verticalScrollBar.minimum
                        LOG.debug { "Script evaluation UI updated." }
                    }
                }
            }

            runButton.addActionListener {
                matchedMethods = linkedSetOf()
                navigationIndex = 0
                onButtonClick("Evaluating...")
            }

            nextButton.addActionListener {
                navigationIndex++
                onButtonClick("Searching next...")
            }

            previousButton.addActionListener {
                navigationIndex--
                onButtonClick("Searching previous...")
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