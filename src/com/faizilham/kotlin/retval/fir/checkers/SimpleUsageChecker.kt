package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.Utils
import com.faizilham.kotlin.retval.fir.isDiscardable
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBlockChecker
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall

// SimpleUsageChecker is an unused return value checker
//   warns any function call with unused value in a block, given it is not discardable
object SimpleUsageChecker : FirBlockChecker(MppCheckerKind.Common) {

    override fun check(expression: FirBlock, context: CheckerContext, reporter: DiagnosticReporter) {
        expression.statements.forEach() {
            if (it is FirFunctionCall && !it.isDiscardable()) {
                reporter.reportOn(it.source, Utils.Warnings.UNUSED_RETURN_VALUE, context)
            }
        }
    }

}
