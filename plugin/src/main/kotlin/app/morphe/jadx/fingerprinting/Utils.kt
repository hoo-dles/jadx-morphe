package app.morphe.jadx.fingerprinting

import com.android.tools.smali.dexlib2.iface.Method

fun Method.getShortId(): String {
    return "${this.name}(${this.parameterTypes.joinToString(separator = "") { it.toString() }})${this.returnType}"
}

