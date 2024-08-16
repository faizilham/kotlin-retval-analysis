package com.faizilham.kotlin.retval.fir

import com.faizilham.kotlin.retval.fir.checkers.SimpleUsageChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class UsageAnalysisExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers = UsageCheckers

    object UsageCheckers : ExpressionCheckers() {
        override val blockCheckers = setOf(SimpleUsageChecker)
    }
}