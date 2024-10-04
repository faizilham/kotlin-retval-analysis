package com.faizilham.kotlin.retval.fir

import com.faizilham.kotlin.retval.fir.attributes.usageObligation
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.warning0
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.isInvoke
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression

object Utils {
    object Constants {
        /* Annotation class ids */
        private val PACKAGE_FQN = FqName("com.faizilham.kotlin.retval")
        val DiscardableClassId = ClassId(PACKAGE_FQN, Name.identifier("Discardable"))
        val MayUseClassId = ClassId(PACKAGE_FQN, Name.identifier("MayUse"))
        val AnyUseClassId = ClassId(PACKAGE_FQN, Name.identifier("AnyUse"))
        val SameUseClassId = ClassId(PACKAGE_FQN, Name.identifier("SameUse"))

        val BuiltInDiscardable : Set<ClassId> = setOf(
            ClassId.fromString("kotlin/contracts/CallsInPlace")
        )
    }

    object Warnings {
        /** Unused return value warning factory */
        val UNUSED_RETURN_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
        val UNUSED_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
    }

    object Errors {
        val SAME_USE_INVALID_TARGET : KtDiagnosticFactory0 by error0<KtExpression>()
        val SAME_USE_NOT_A_FUNCTION : KtDiagnosticFactory0 by error0<KtExpression>()
        val SAME_USE_MISMATCH_RETURN_TYPE : KtDiagnosticFactory0 by error0<KtExpression>()
    }
}

fun FirFunctionCall.isDiscardable() : Boolean {
    if (resolvedType.isDiscardable()) {
        return true
    }

    return hasDiscardableAnnotation(this)
}

fun FirCallableReferenceAccess.isDiscardable() : Boolean {
    val returnType = resolvedType.typeArguments.firstOrNull()?.type
    if (returnType?.isDiscardable() == true) {
        return true
    }

    return hasDiscardableAnnotation(this)
}

fun hasDiscardableAnnotation(fir: FirQualifiedAccessExpression) : Boolean {
    val funcSymbol = fir.calleeReference.toResolvedFunctionSymbol() ?: return false

    return funcSymbol.containsAnnotation(Utils.Constants.DiscardableClassId)
}

fun FirBasedSymbol<*>.containsAnnotation(classId: ClassId) : Boolean {
    return resolvedAnnotationClassIds.contains(classId)
}

fun FirFunctionCall.isInvoke() = calleeReference.toResolvedFunctionSymbol()?.callableId?.isInvoke() ?: false

fun ConeKotlinType.isDiscardable() : Boolean {
    return isUnitOrNullableUnit ||
            isBuiltInDiscardable(classId) ||
            (attributes.usageObligation?.isMayUse() ?: false)
}

fun isBuiltInDiscardable(classId: ClassId?) : Boolean {
    return classId != null && Utils.Constants.BuiltInDiscardable.contains(classId)
}
