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
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtExpression

class SimpleUsageAnalysis(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers = UsageCheckers

    companion object {
        val DISCARDABLE_CLASS_ID = ClassId.fromString("org/faizilham/kotlin/retval/Discardable")
    }

    object UsageCheckers : ExpressionCheckers() {
        override val blockCheckers = setOf(SimpleUsageChecker)
    }

    object SimpleUsageChecker : FirBlockChecker(MppCheckerKind.Common) {
        private val UNUSED_RETURN_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()

        override fun check(expression: FirBlock, context: CheckerContext, reporter: DiagnosticReporter) {
            expression.statements.forEach {
                if (it !is FirFunctionCall || isDiscardable(it)) {
                    return@forEach
                }

                reporter.reportOn(it.source, UNUSED_RETURN_VALUE, context)
            }
        }

        private fun isDiscardable(expr: FirFunctionCall) : Boolean {
            val hasDiscardable = expr.calleeReference.toResolvedFunctionSymbol()?.resolvedAnnotationClassIds?.any {
                it == DISCARDABLE_CLASS_ID
            } ?: false

            return expr.resolvedType.isUnit || hasDiscardable
        }
    }
}