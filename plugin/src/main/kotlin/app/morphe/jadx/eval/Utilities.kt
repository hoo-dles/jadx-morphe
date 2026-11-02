package app.morphe.jadx.eval

import com.android.tools.smali.dexlib2.iface.Method

fun Method.getShortId(): String {
    return "${this.name}(${this.parameterTypes.joinToString(separator = "") { it.toString() }})${this.returnType}"
}

/**
 * Code unit offset of each instruction, indexed by instruction index.
 */
fun Method.instructionCodeOffsets(): List<Int> {
    var offset = 0
    return implementation?.instructions?.map { instruction ->
        offset.also { offset += instruction.codeUnits }
    }.orEmpty()
}
