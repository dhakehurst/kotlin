// WITH_RUNTIME

val xs = Array(2) { 42u }

fun box(): String {
    xs[0] = 12u
    val t = xs[0]
    if (t != 12u) throw AssertionError("$t")

    return "OK"
}