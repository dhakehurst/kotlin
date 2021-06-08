/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package runtime.basic.tostring4

import kotlin.test.*

class TopLevel

@Test fun runTest() {
    TopLevel().printWithoutHashCode()

    class Local1
    Local1().printWithoutHashCode()

    object {}.printWithoutHashCode()

    fun localFun() {
        class Local2
        Local2().printWithoutHashCode()

        object {}.printWithoutHashCode()
    }
    localFun()
}

private fun Any.printWithoutHashCode(): Unit =
        with(toString()) { println(if ('@' in this) substringBefore('@') else this + " [NO @ IN TEXT RETURNED BY toString()]") }

