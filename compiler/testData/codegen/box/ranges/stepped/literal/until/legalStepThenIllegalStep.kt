// Auto-generated by GenerateSteppedRangesCodegenTestData. Do not edit!
// WITH_RUNTIME
import kotlin.test.*

fun box(): String {
    assertFailsWith<IllegalArgumentException> {
        for (i in 1 until 8 step 2 step 0) {
        }
    }

    assertFailsWith<IllegalArgumentException> {
        for (i in 1L until 8L step 2L step 0L) {
        }
    }

    assertFailsWith<IllegalArgumentException> {
        for (i in 'a' until 'h' step 2 step 0) {
        }
    }

    return "OK"
}