package com.faizilham.kotlin.retval.fir

import com.faizilham.kotlin.retval.fir.checkers.SimpleUsageChecker
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.warning0
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtExpression

class UsageAnalysisExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers = UsageCheckers

    companion object {
        /** Discardable annotation class id */
        val DISCARDABLE_CLASS_ID = ClassId.fromString("com/faizilham/kotlin/retval/Discardable")

        /** Unused return value warning factory */
        val UNUSED_RETURN_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
    }

    object UsageCheckers : ExpressionCheckers() {
        override val blockCheckers = setOf(SimpleUsageChecker)
    }
}