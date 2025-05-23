// TARGET_BACKEND: JVM
// ^ This is no supposed to work on KLIB-backends, see KT-69681
// FILE: 1.kt

package test

private class S public constructor() {
    class Z {
        fun a(): String {
            return "K"
        }

        val empty = ""
    }

    enum class E(val s: String) {
        EMPTY("")
    }
}
// This function exposes S.Z and S.E, nested into a private class S (package-private in the byte code)
// They can be accessed outside the `test` package now that S.Z, S.E are public in the byte code, but that may be changed later
@Suppress("IR_PRIVATE_TYPE_USED_IN_NON_PRIVATE_INLINE_FUNCTION_ERROR")
internal inline fun call(s: () -> String): String {
    return s() + S.Z().empty + S.E.EMPTY.s + S.Z().a()
}

// FILE: 2.kt

import test.*

fun box(): String {
    return call {
        "O"
    }
}
