package com.faizilham.kotlin.retval.fir

import com.faizilham.kotlin.retval.fir.attributes.usageObligation
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.warning0
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isUnitOrNullableUnit
import org.jetbrains.kotlin.fir.types.resolvedType
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

        val BuiltInDiscardable : Set<ClassId> = setOf(
            ClassId.fromString("kotlin/contracts/CallsInPlace")
        )
    }

    object Warnings {
        /** Unused return value warning factory */
        val UNUSED_RETURN_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
    }
}

fun FirFunctionCall.isDiscardable() : Boolean {
    if (resolvedType.isDiscardable()) {
        return true
    }

    val annotations = calleeReference.toResolvedFunctionSymbol()?.resolvedAnnotationClassIds
    val hasDiscardable = annotations?.any { it == Utils.Constants.DiscardableClassId } ?: false

    return hasDiscardable
}

fun ConeKotlinType.isDiscardable() : Boolean {
    return isUnitOrNullableUnit ||
            isBuiltInDiscardable(classId) ||
            (attributes.usageObligation?.isMayUse() ?: false)
}

fun isBuiltInDiscardable(classId: ClassId?) : Boolean {
    return classId != null && Utils.Constants.BuiltInDiscardable.contains(classId)
}
