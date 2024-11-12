package com.faizilham.kotlin.retval.fir

import com.faizilham.kotlin.retval.fir.checkers.SameUseAnnotationChecker
import com.faizilham.kotlin.retval.fir.checkers.UsageFlowChecker
import com.faizilham.kotlin.retval.fir.checkers.UtilizationBackChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension


class UsageAnalysisExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers = DeclCheckers

    object DeclCheckers : DeclarationCheckers() {
        override val controlFlowAnalyserCheckers = setOf(
            UsageFlowChecker,
//            UtilizationChecker,
            UtilizationBackChecker
        )
    }

    override val expressionCheckers = ExprCheckers

    object ExprCheckers : ExpressionCheckers() {
        override val annotationCheckers = setOf(SameUseAnnotationChecker)
    }
}