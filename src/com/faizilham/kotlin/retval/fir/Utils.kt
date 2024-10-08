package com.faizilham.kotlin.retval.fir

import com.faizilham.kotlin.retval.fir.attributes.usageObligation
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.warning0
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
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
        private val PACKAGE_FQN = FqName("com.faizilham.kotlin.retval.annotations")
        val DiscardableClassId = ClassId(PACKAGE_FQN, Name.identifier("Discardable"))
        val MustConsumeClassId = ClassId(PACKAGE_FQN, Name.identifier("MustConsume"))
        val ConsumeClassId = ClassId(PACKAGE_FQN, Name.identifier("Consume"))
        val SameUseClassId = ClassId(PACKAGE_FQN, Name.identifier("SameUse"))

        val MayUseClassId = ClassId(PACKAGE_FQN, Name.identifier("MayUse"))
        val AnyUseClassId = ClassId(PACKAGE_FQN, Name.identifier("AnyUse"))

        val BuiltInDiscardable : Set<ClassId> = setOf(
            ClassId.fromString("kotlin/contracts/CallsInPlace")
        )
    }

    object Warnings {
        /** Unused return value warning factory */
        val UNUSED_RETURN_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
        val UNUSED_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
        val UNCONSUMED_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
    }

    object Errors {
        val SAME_USE_INVALID_TARGET : KtDiagnosticFactory0 by error0<KtExpression>()
        val SAME_USE_NOT_A_FUNCTION : KtDiagnosticFactory0 by error0<KtExpression>()
        val SAME_USE_MISMATCH_RETURN_TYPE : KtDiagnosticFactory0 by error0<KtExpression>()
        val CONSUME_NOT_MEMBER_OR_EXT : KtDiagnosticFactory0 by error0<KtExpression>()
    }
}

fun CFGNode<*>.isInvalidNext(current: CFGNode<*>) = isDead || edgeFrom(current).kind.isBack
fun CFGNode<*>.isInvalidPrev(current: CFGNode<*>) = isDead || edgeTo(current).kind.isBack

fun CFGNode<*>.validNextSize() =
    this.followingNodes.asSequence().filterNot { it.isInvalidNext(this) }.count()


fun FirFunctionCall.isDiscardable(session: FirSession) : Boolean {
    if (resolvedType.isDiscardable(session)) {
        return true
    }

    return hasDiscardableAnnotation(session)
}

fun FirCallableReferenceAccess.isDiscardable(session: FirSession) : Boolean {
    val returnType = resolvedType.typeArguments.firstOrNull()?.type
    if (returnType?.isDiscardable(session) == true) {
        return true
    }

    return hasDiscardableAnnotation(session)
}

fun FirQualifiedAccessExpression.hasDiscardableAnnotation(session: FirSession) : Boolean {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return false

    return funcSymbol.hasAnnotation(Utils.Constants.DiscardableClassId, session)
}

fun FirFunctionCall.hasConsumeAnnotation(session: FirSession) : Boolean {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return false

    return funcSymbol.hasAnnotation(Utils.Constants.ConsumeClassId, session)
}

fun FirBasedSymbol<*>.containsAnnotation(classId: ClassId) : Boolean {
    return resolvedAnnotationClassIds.contains(classId)
}

fun FirFunctionCall.isInvoke() = calleeReference.toResolvedFunctionSymbol()?.callableId?.isInvoke() ?: false

fun ConeKotlinType.isDiscardable(session: FirSession) : Boolean {
    return  isUnitOrNullableUnit ||
            isBuiltInDiscardable(classId) ||
            hasDiscardableAnnotation(session) ||
            (attributes.usageObligation?.isMayUse() ?: false)
}

fun ConeKotlinType.hasDiscardableAnnotation(session: FirSession) =
    hasClassAnnotation(session, Utils.Constants.DiscardableClassId)

fun ConeKotlinType.hasMustConsumeAnnotation(session: FirSession) =
    hasClassAnnotation(session, Utils.Constants.MustConsumeClassId)

fun ConeKotlinType.hasClassAnnotation(session: FirSession, classId: ClassId) : Boolean{
    val regularClassSymbol = toRegularClassSymbol(session) ?: return false
    return regularClassSymbol.hasAnnotation(classId, session)
}

fun isBuiltInDiscardable(classId: ClassId?) : Boolean {
    return classId != null && Utils.Constants.BuiltInDiscardable.contains(classId)
}
