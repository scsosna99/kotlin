/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.types.Variance

object FirTypeParameterVarianceChecker : FirTypeParameterChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirTypeParameter) {
        val containingDeclaration = context.containingDeclarations.lastOrNull()
        if (declaration.variance != Variance.INVARIANT &&
            (containingDeclaration is FirNamedFunctionSymbol ||
                    containingDeclaration is FirTypeAliasSymbol ||
                    containingDeclaration is FirPropertySymbol)
        ) {
            reporter.reportOn(declaration.source, FirErrors.VARIANCE_ON_TYPE_PARAMETER_NOT_ALLOWED)
        }
    }
}
