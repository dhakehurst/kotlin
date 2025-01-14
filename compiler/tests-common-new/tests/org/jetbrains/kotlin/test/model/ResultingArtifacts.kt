/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.model

import org.jetbrains.kotlin.codegen.ClassFileFactory
import org.jetbrains.kotlin.incremental.js.TranslationResultValue
import org.jetbrains.kotlin.ir.backend.js.CompilerResult
import org.jetbrains.kotlin.js.backend.ast.JsProgram
import org.jetbrains.kotlin.js.facade.TranslationResult
import java.io.File

object BinaryArtifacts {
    class Jvm(val classFileFactory: ClassFileFactory) : ResultingArtifact.Binary<Jvm>() {
        override val kind: BinaryKind<Jvm>
            get() = ArtifactKinds.Jvm
    }

    sealed class Js : ResultingArtifact.Binary<Js>() {
        abstract val outputFile: File
        override val kind: BinaryKind<Js>
            get() = ArtifactKinds.Js

        open fun unwrap(): Js = this

        class OldJsArtifact(override val outputFile: File, val translationResult: TranslationResult) : Js()
        class JsIrArtifact(override val outputFile: File, val compilerResult: CompilerResult) : Js()

        data class IncrementalJsArtifact(val originalArtifact: Js, val recompiledArtifact: Js) : Js() {
            override val outputFile: File
                get() = unwrap().outputFile

            override fun unwrap(): Js {
                return originalArtifact
            }
        }
    }

    class Native : ResultingArtifact.Binary<Native>() {
        override val kind: BinaryKind<Native>
            get() = ArtifactKinds.Native
    }

    class KLib : ResultingArtifact.Binary<KLib>() {
        override val kind: BinaryKind<KLib>
            get() = ArtifactKinds.KLib
    }
}
