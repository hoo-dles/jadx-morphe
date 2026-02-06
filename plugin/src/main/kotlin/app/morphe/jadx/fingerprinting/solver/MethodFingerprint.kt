package app.morphe.jadx.fingerprinting.solver

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

fun Method.getUniqueId(): String {
    return "${this.definingClass}${this.name}(${this.parameterTypes.joinToString(separator = "") { it.toString() }})${this.returnType}"
}

data class MethodFingerprint(
    val id: String,
    val accessFlags: Int,
    val returnType: String,
    val parameters: List<String>,
    val opcodes: List<Opcode>,
    val strings: List<String>,
)

fun Method.toMethodFingerprint(): MethodFingerprint {
    return MethodFingerprint(
        accessFlags = this.accessFlags,
        returnType = this.returnType,
        parameters = this.parameters.map { it.type },
        opcodes = this.instructionsOrNull?.map { it.opcode } ?: emptyList(),
        id = this.getUniqueId(),
        strings = this.instructionsOrNull?.mapNotNull { instruction ->
            if (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) {
                (instruction as ReferenceInstruction).reference as? StringReference
            } else {
                null
            }
        }?.map { it.string } ?: emptyList(),
    )
}