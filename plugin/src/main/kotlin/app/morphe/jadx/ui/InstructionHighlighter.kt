package app.morphe.jadx.ui

import app.morphe.jadx.Log
import jadx.api.JavaMethod
import jadx.gui.device.debugger.smali.Smali
import jadx.gui.treemodel.JClass
import jadx.gui.ui.MainWindow
import jadx.gui.ui.codearea.ClassCodeContentPanel
import jadx.gui.ui.codearea.SmaliArea
import java.awt.Color
import javax.swing.Timer

/**
 * Highlights matched instructions in the smali bytecode view of a class tab.
 *
 * This uses jadx GUI internals (the same smali printer the jadx debugger uses),
 * so it may need updating when jadx changes.
 */
object InstructionHighlighter {
    // Same color the jadx debugger uses for the current instruction.
    private val HIGHLIGHT_COLOR = Color.decode("#9c1138")
    private const val POLL_INTERVAL_MS = 100
    private const val TIMEOUT_MS = 10_000

    private var pollTimer: Timer? = null
    private var highlightedArea: SmaliArea? = null
    private val highlightTags = mutableListOf<Any>()

    /**
     * Must be called on the Swing event thread, after the method was opened.
     */
    fun highlight(mainWindow: MainWindow, method: JavaMethod, codeOffsets: List<Int>) {
        pollTimer?.stop()
        if (codeOffsets.isEmpty()) return

        try {
            val methodNode = method.methodNode
            val topClass = methodNode.topParentClass
            val rawMethodId = methodNode.methodInfo.rawFullId
            val smali = Smali.disassemble(topClass)
            val positions = codeOffsets
                .map { smali.getInsnPosByCodeOffset(rawMethodId, it.toLong()) }
                .filter { it >= 0 }
            if (positions.isEmpty()) {
                Log.warn { "Could not find smali positions of matched instructions in $rawMethodId" }
                return
            }

            // The class tab and its smali text are loaded in the background, so wait for them.
            val startTime = System.currentTimeMillis()
            var switchedToBytecode = false
            pollTimer = Timer(POLL_INTERVAL_MS) {
                try {
                    val smaliArea = findSmaliArea(mainWindow, topClass)
                    if (smaliArea != null) {
                        if (!switchedToBytecode) {
                            (smaliArea.contentPanel as ClassCodeContentPanel).showSmaliPane()
                            // Switches the smali view to the bytecode view if needed.
                            smaliArea.scrollToDebugPos(positions.first())
                            switchedToBytecode = true
                        } else if (smaliArea.isLoaded && smaliArea.text == smali.code) {
                            pollTimer?.stop()
                            applyHighlights(smaliArea, positions)
                            return@Timer
                        }
                    }

                    if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                        pollTimer?.stop()
                        Log.warn { "Timed out waiting for smali view of ${topClass.fullName}" }
                    }
                } catch (ex: Exception) {
                    pollTimer?.stop()
                    Log.error(ex) { "Failed to highlight matched instructions" }
                }
            }.apply { start() }
        } catch (ex: Exception) {
            Log.error(ex) { "Failed to highlight matched instructions" }
        }
    }

    private fun findSmaliArea(mainWindow: MainWindow, topClass: jadx.core.dex.nodes.ClassNode) =
        mainWindow.tabbedPane.tabs
            .filterIsInstance<ClassCodeContentPanel>()
            .firstOrNull { (it.node as? JClass)?.cls?.classNode == topClass }
            ?.smaliCodeArea as? SmaliArea

    private fun applyHighlights(smaliArea: SmaliArea, positions: List<Int>) {
        clearHighlights()

        // Marks the first instruction, and replaces any highlight left from before the text was loaded.
        smaliArea.scrollToDebugPos(positions.first())
        positions.drop(1).forEach { position ->
            highlightTags.add(smaliArea.addLineHighlight(smaliArea.getLineOfOffset(position), HIGHLIGHT_COLOR))
        }
        highlightedArea = smaliArea

        smaliArea.scrollToPos(positions.first())
    }

    private fun clearHighlights() {
        highlightedArea?.let { area ->
            if (!area.isDisposed) {
                highlightTags.forEach { area.removeLineHighlight(it) }
            }
        }
        highlightTags.clear()
        highlightedArea = null
    }
}
