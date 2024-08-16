package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.Utils
import com.faizilham.kotlin.retval.fir.attributes.usageObligation
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBlockChecker
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isUnitOrNullableUnit
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId

// SimpleUsageChecker is an unused return value checker
//   warns any function call with unused value in a block, given it is not discardable
object SimpleUsageChecker : FirBlockChecker(MppCheckerKind.Common) {

    override fun check(expression: FirBlock, context: CheckerContext, reporter: DiagnosticReporter) {
        expression.statements.forEach() {
            if (it is FirFunctionCall && !isDiscardable(it)) {
                reporter.reportOn(it.source, Utils.Warnings.UNUSED_RETURN_VALUE, context)
            }
        }
    }

    private fun isDiscardable(expr: FirFunctionCall) : Boolean {
        if (isDiscardableType(expr.resolvedType)) {
            return true
        }

        val annotations = expr.calleeReference.toResolvedFunctionSymbol()?.resolvedAnnotationClassIds
        val hasDiscardable = annotations?.any { it == Utils.Constants.DiscardableClassId } ?: false

        return hasDiscardable
    }

    private fun isDiscardableType(type: ConeKotlinType) : Boolean {
        return type.isUnitOrNullableUnit ||
               isBuiltInDiscardable(type.classId) ||
               (type.attributes.usageObligation?.isMayUse() ?: false)
    }

    private fun isBuiltInDiscardable(classId: ClassId?) : Boolean {
        return classId != null && builtInDiscardable.contains(classId)
    }

    // Hard-coded class to be discardable, work around until it can be annotated
    private val builtInDiscardable : Set<ClassId> = setOf(
        ClassId.fromString("kotlin/contracts/CallsInPlace")
    )
}
