package org.demiurg906.kotlin.plugin.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.diagnostics.warning0
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBlockChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.psi.KtExpression

class SimpleUsageAnalysis(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers = UsageCheckers

    object UsageCheckers : ExpressionCheckers() {
        override val blockCheckers = setOf(BlockUsageChecker)
    }

    object BlockUsageChecker : FirBlockChecker(MppCheckerKind.Common) {
        private val UNUSED_RETURN_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()

        override fun check(expression: FirBlock, context: CheckerContext, reporter: DiagnosticReporter) {
            expression.statements.forEach {
                if (it !is FirFunctionCall) return@forEach

                if (it.calleeReference.name.asString() == "normal") {
                    reporter.reportOn(it.source, UNUSED_RETURN_VALUE, context)
                }
            }
        }
    }
}