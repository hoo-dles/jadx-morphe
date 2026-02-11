package app.morphe.jadx.ui.components

import javax.swing.BorderFactory
import javax.swing.JTextArea

class TextArea(text: String, wrap: Boolean = false) : JTextArea(text) {
    init {
        lineWrap = wrap
        wrapStyleWord = true
        isEditable = false
        alignmentX = LEFT_ALIGNMENT
        alignmentY = TOP_ALIGNMENT
        setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2))
    }
}