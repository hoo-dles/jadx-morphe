package app.morphe.jadx.ui

import app.morphe.jadx.Log
import app.morphe.jadx.eval.FingerprintMatch
import app.morphe.jadx.eval.MatchedInstruction
import jadx.core.dex.nodes.ClassNode
import jadx.gui.treemodel.JClass
import jadx.gui.ui.MainWindow
import jadx.gui.ui.codearea.ClassCodeContentPanel
import jadx.gui.ui.codearea.SmaliArea
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Highlights matched instructions in the smali view of a class tab.
 *
 * This uses jadx GUI internals, so it may need updating when jadx changes.
 */
object InstructionHighlighter {
    // Semi-transparent so the text stays readable with light and dark themes.
    private val HIGHLIGHT_COLOR = Color(255, 210, 0, 70)
    private const val POLL_INTERVAL_MS = 100
    private const val TIMEOUT_MS = 10_000

    // Directives with a body that is not made of instructions.
    private val BLOCK_DIRECTIVES = listOf(".annotation", ".packed-switch", ".sparse-switch", ".array-data")

    private var pollTimer: Timer? = null
    private var highlightedArea: SmaliArea? = null
    private var reloadListener: DocumentListener? = null
    private val highlightTags = mutableListOf<Any>()

    /**
     * Must be called on the Swing event thread, after the method was opened.
     */
    fun highlight(mainWindow: MainWindow, topClass: ClassNode, match: FingerprintMatch) {
        pollTimer?.stop()
        if (match.matchedInstructions.isEmpty()) return

        // The class tab and its smali text are loaded in the background, so wait for them.
        val startTime = System.currentTimeMillis()
        var shownSmaliPane = false
        pollTimer = Timer(POLL_INTERVAL_MS) {
            var smaliArea: SmaliArea? = null
            try {
                val classPanel = findClassPanel(mainWindow, topClass)
                smaliArea = classPanel?.let { findSmaliArea(it) }
                if (classPanel != null && smaliArea != null) {
                    if (!shownSmaliPane) {
                        showArea(classPanel, smaliArea)
                        shownSmaliPane = true
                    } else if (smaliArea.isLoaded) {
                        val positions = findInstructionPositions(smaliArea.text, match)
                        if (positions != null) {
                            pollTimer?.stop()
                            if (positions.isNotEmpty()) startHighlighting(smaliArea, match, positions)
                            return@Timer
                        }
                    }
                }

                if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
                    pollTimer?.stop()
                    Log.warn {
                        "Could not find matched instructions of ${match.definingClass}->${match.methodShortId}" +
                            " in smali view (tab found: ${smaliArea != null}, loaded: ${smaliArea?.isLoaded})"
                    }
                }
            } catch (ex: Exception) {
                pollTimer?.stop()
                Log.error(ex) { "Failed to highlight matched instructions" }
            }
        }.apply { start() }
    }

    private fun findClassPanel(mainWindow: MainWindow, topClass: ClassNode) =
        mainWindow.tabbedPane.tabs
            .filterIsInstance<ClassCodeContentPanel>()
            .firstOrNull { (it.node as? JClass)?.cls?.classNode == topClass }

    private fun findSmaliArea(classPanel: ClassCodeContentPanel): SmaliArea? {
        // Skip the "Smali bytecode" tab, which also uses a SmaliArea.
        val smaliAreas = classPanel.descendants()
            .filterIsInstance<SmaliArea>()
            .filter { !it.isShowingDalvikBytecode }
            .toList()

        // In split view, prefer the pane that is not showing the decompiled Java code.
        val javaTabbedPane = SwingUtilities.getAncestorOfClass(JTabbedPane::class.java, classPanel.javaCodePanel)
        return smaliAreas.firstOrNull {
            SwingUtilities.getAncestorOfClass(JTabbedPane::class.java, it) != javaTabbedPane
        } ?: smaliAreas.firstOrNull()
    }

    private fun showArea(classPanel: ClassCodeContentPanel, smaliArea: SmaliArea) {
        val tabbedPane = SwingUtilities.getAncestorOfClass(JTabbedPane::class.java, smaliArea) as? JTabbedPane
        val tab = tabbedPane?.components?.firstOrNull { SwingUtilities.isDescendingFrom(smaliArea, it) }
        if (tab != null) {
            tabbedPane.selectedComponent = tab
        } else {
            classPanel.showSmaliPane()
        }
    }

    private fun Component.descendants(): Sequence<Component> = sequence {
        yield(this@descendants)
        if (this@descendants is Container) {
            components.forEach { yieldAll(it.descendants()) }
        }
    }

    /**
     * Finds the text positions of the matched instructions, by counting the instruction lines
     * of the method in the smali text. Each line found is checked against the matched opcode.
     *
     * @return The positions, or null if the method is not in the smali text.
     */
    private fun findInstructionPositions(smaliText: String, match: FingerprintMatch): List<Int>? {
        val wanted = match.matchedInstructions.associateBy(MatchedInstruction::index)
        val lastWantedIndex = wanted.keys.max()
        val positions = mutableListOf<Int>()

        var inClass = false
        var inMethod = false
        var blockEnd: String? = null
        var instructionIndex = 0
        var lineStart = 0

        for (line in smaliText.lineSequence()) {
            val position = lineStart
            lineStart += line.length + 1
            val trimmed = line.trim()

            if (!inMethod) {
                if (trimmed.startsWith(".class ")) {
                    inClass = trimmed.endsWith(" ${match.definingClass}")
                } else if (inClass && trimmed.startsWith(".method ") && trimmed.endsWith(" ${match.methodShortId}")) {
                    inMethod = true
                }
                continue
            }

            if (blockEnd != null) {
                if (trimmed.startsWith(blockEnd)) blockEnd = null
                continue
            }
            if (trimmed.startsWith(".end method")) break

            val blockDirective = BLOCK_DIRECTIVES.firstOrNull { trimmed.startsWith(it) }
            if (blockDirective != null) {
                blockEnd = ".end ${blockDirective.removePrefix(".")}"
                continue
            }
            if (trimmed.isEmpty() || trimmed[0] in ".:#") continue

            wanted[instructionIndex]?.let { instruction ->
                val lineOpcode = trimmed.substringBefore(' ')
                if (normalizeOpcode(lineOpcode) == normalizeOpcode(instruction.opcodeName)) {
                    positions.add(position + line.indexOf(lineOpcode))
                } else {
                    Log.warn {
                        "Smali line of instruction $instructionIndex is $lineOpcode," +
                            " expected ${instruction.opcodeName}: ${match.definingClass}->${match.methodShortId}"
                    }
                }
            }
            if (instructionIndex == lastWantedIndex) break
            instructionIndex++
        }

        return if (inMethod) positions else null
    }

    private fun normalizeOpcode(name: String) = name.uppercase().replace('-', '_').replace('/', '_')

    private fun startHighlighting(smaliArea: SmaliArea, match: FingerprintMatch, positions: List<Int>) {
        clearHighlights()
        highlightedArea = smaliArea
        applyHighlights(smaliArea, positions)

        // jadx can load the smali text again right after a class tab is opened. Loading replaces
        // the text and moves the caret to the top, so highlight and scroll again when that happens.
        var reapplyQueued = false
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = queueReapply()
            override fun removeUpdate(e: DocumentEvent) = queueReapply()
            override fun changedUpdate(e: DocumentEvent) {}

            private fun queueReapply() {
                if (reapplyQueued) return
                reapplyQueued = true
                SwingUtilities.invokeLater {
                    reapplyQueued = false
                    try {
                        if (highlightedArea != smaliArea || !smaliArea.isLoaded) return@invokeLater
                        findInstructionPositions(smaliArea.text, match)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { applyHighlights(smaliArea, it) }
                    } catch (ex: Exception) {
                        Log.error(ex) { "Failed to highlight matched instructions after reload" }
                    }
                }
            }
        }
        smaliArea.document.addDocumentListener(listener)
        reloadListener = listener
    }

    private fun applyHighlights(smaliArea: SmaliArea, positions: List<Int>) {
        removeHighlightTags(smaliArea)
        positions.forEach { position ->
            highlightTags.add(smaliArea.addLineHighlight(smaliArea.getLineOfOffset(position), HIGHLIGHT_COLOR))
        }

        // Scroll after the pending layout is done, and without moving the caret,
        // as the current line highlight hides the match highlight.
        SwingUtilities.invokeLater {
            val visibleRect = smaliArea.visibleRect
            smaliArea.modelToView2D(positions.first())?.bounds?.let { matchRect ->
                smaliArea.scrollRectToVisible(
                    Rectangle(
                        visibleRect.x,
                        matchRect.y - visibleRect.height / 3,
                        visibleRect.width,
                        visibleRect.height
                    )
                )
            }
        }
    }

    private fun removeHighlightTags(smaliArea: SmaliArea) {
        if (!smaliArea.isDisposed) {
            highlightTags.forEach { smaliArea.removeLineHighlight(it) }
        }
        highlightTags.clear()
    }

    private fun clearHighlights() {
        highlightedArea?.let { area ->
            removeHighlightTags(area)
            reloadListener?.let { area.document.removeDocumentListener(it) }
        }
        reloadListener = null
        highlightedArea = null
    }
}
