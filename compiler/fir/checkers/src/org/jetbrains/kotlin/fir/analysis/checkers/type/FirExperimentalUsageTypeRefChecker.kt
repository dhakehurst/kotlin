/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.type

import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExperimentalUsageBaseChecker
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneTypeSafe

object FirExperimentalUsageTypeRefChecker : FirTypeRefChecker() {
    override fun check(typeRef: FirTypeRef, context: CheckerContext, reporter: DiagnosticReporter) {
        val coneType = typeRef.coneTypeSafe<ConeClassLikeType>() ?: return
        val fir = coneType.lookupTag.toSymbol(context.session)?.fir ?: return
        with(FirExperimentalUsageBaseChecker) {
            val experimentalities = fir.loadExperimentalities(context) +
                    loadExperimentalitiesFromConeArguments(context, coneType.typeArguments.toList())
            reportNotAcceptedExperimentalities(experimentalities, typeRef, context, reporter)
        }
    }
}