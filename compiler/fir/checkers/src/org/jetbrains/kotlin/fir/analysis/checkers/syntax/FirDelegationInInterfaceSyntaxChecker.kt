/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.syntax

import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.fir.FirLightSourceElement
import org.jetbrains.kotlin.fir.FirPsiSourceElement
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry

object FirDelegationInInterfaceSyntaxChecker : FirDeclarationSyntaxChecker<FirRegularClass, KtClass>() {

    override fun isApplicable(element: FirRegularClass, source: FirSourceElement): Boolean = element.isInterface

    override fun checkPsi(
        element: FirRegularClass,
        source: FirPsiSourceElement,
        psi: KtClass,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        for (superTypeRef in element.superTypeRefs) {
            val superSource = superTypeRef.source ?: continue
            if (superSource.psi?.parent is KtDelegatedSuperTypeEntry) {
                reporter.reportOn(superSource, FirErrors.DELEGATION_IN_INTERFACE, context)
            }
        }
    }

    override fun checkLightTree(
        element: FirRegularClass,
        source: FirLightSourceElement,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        for (superTypeRef in element.superTypeRefs) {
            val superSource = superTypeRef.source ?: continue
            if (superSource.treeStructure.getParent(superSource.lighterASTNode)?.tokenType == KtNodeTypes.DELEGATED_SUPER_TYPE_ENTRY) {
                reporter.reportOn(superSource, FirErrors.DELEGATION_IN_INTERFACE, context)
            }
        }
    }
}
