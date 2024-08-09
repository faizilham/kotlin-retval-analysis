package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.UsageAnalysisExtension
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBlockChecker
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.isUnitOrNullableUnit
import org.jetbrains.kotlin.fir.types.resolvedType

object SimpleUsageChecker : FirBlockChecker(MppCheckerKind.Common) {
    override fun check(expression: FirBlock, context: CheckerContext, reporter: DiagnosticReporter) {
        expression.statements.forEach() {
            if (it is FirFunctionCall && !isDiscardable(it)) {
                reporter.reportOn(it.source, UsageAnalysisExtension.UNUSED_RETURN_VALUE, context)
            }
        }
    }

    private fun isDiscardable(expr: FirFunctionCall) : Boolean {
        if (isDiscardableType(expr.resolvedType)) {
            return true
        }

        val annotations = expr.calleeReference.toResolvedFunctionSymbol()?.resolvedAnnotationClassIds
        val hasDiscardable = annotations?.any { it == UsageAnalysisExtension.DISCARDABLE_CLASS_ID } ?: false

        return hasDiscardable
    }

    private fun isDiscardableType(type: ConeKotlinType) : Boolean {
        return type.isUnitOrNullableUnit
    }
}