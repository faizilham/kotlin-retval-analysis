package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.Utils
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirAnnotationChecker
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.fir.types.returnType

object SameUseAnnotationChecker : FirAnnotationChecker(MppCheckerKind.Common) {
    override fun check(expression: FirAnnotation, context: CheckerContext, reporter: DiagnosticReporter) {
        val session = context.session
        val annotationId = expression.toAnnotationClassId(session)

        if (annotationId != Utils.Constants.SameUseClassId) return

        val annotationTarget = context.containingDeclarations.last()
        val param = annotationTarget as? FirValueParameter
            ?: return reporter.reportOn(annotationTarget.source, Utils.Errors.SAME_USE_INVALID_TARGET, context)

        val funcRetType = param.containingFunctionSymbol.resolvedReturnType
        val paramType = param.returnTypeRef.coneType

        if (!paramType.isSomeFunctionType(session)) {
            reporter.reportOn(param.source, Utils.Errors.SAME_USE_NOT_A_FUNCTION, context)
            return
        }

        if (!paramType.returnType(session).isSubtypeOf(funcRetType, session)) {
            reporter.reportOn(param.source, Utils.Errors.SAME_USE_MISMATCH_RETURN_TYPE, context)
        }
    }
}