// WITH_RUNTIME

abstract class BaseClass {
    protected open val menuId: Int = 0

    public fun run(): Pair<String, Boolean> =
            "$menuId" to (menuId == 0)
}

class ImplClass: BaseClass() {
    override val menuId: Int = 3
}

fun box(): String {
    val result = ImplClass().run()

    if (result != ("3" to false)) return "Fail: $result"

    return "OK"
}
