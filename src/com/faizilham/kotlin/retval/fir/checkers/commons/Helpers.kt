package com.faizilham.kotlin.retval.fir.checkers.commons

import com.faizilham.kotlin.retval.fir.attributes.usageObligation
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.resolve.isInvoke
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.isExtension
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.ClassId

/* Control Flow Helpers */

fun ControlFlowGraph.isLambda() =
    kind == ControlFlowGraph.Kind.AnonymousFunction ||
            kind == ControlFlowGraph.Kind.AnonymousFunctionCalledInPlace

fun CFGNode<*>.isInvalidNext(current: CFGNode<*>) = isDead || edgeFrom(current).kind.isBack

fun CFGNode<*>.isInvalidPrev(current: CFGNode<*>) = isDead || edgeTo(current).kind.isBack

fun CFGNode<*>.validNextSize() =
    this.followingNodes.asSequence().filterNot { it.isInvalidNext(this) }.count()


fun CFGNode<*>.isReturnNode(): Boolean {
    val nextIsExit = followingNodes.firstOrNull() is FunctionExitNode ||
            followingNodes.lastOrNull() is FunctionExitNode

    return  (this is JumpNode && nextIsExit) ||
            (this is BlockExitNode && nextIsExit && owner.isLambda())
}

fun CFGNode<*>.isIndirectValueSource(): Boolean {
    return  (this is JumpNode) ||
            (this is BlockExitNode) ||
            (this is WhenExitNode) ||
            (this is WhenBranchResultExitNode) ||
            (this is ElvisLhsExitNode) ||
            (this is ElvisLhsIsNotNullNode) ||
            (this is ElvisExitNode) ||
            (this is BinaryAndExitLeftOperandNode) ||
            (this is BinaryAndExitNode) ||
            (this is BinaryOrExitLeftOperandNode) ||
            (this is BinaryOrExitNode)
}

/* Fir Discardable and Utilization Helpers */

fun FirFunctionCall.isDiscardable(session: FirSession) : Boolean {
    if (resolvedType.isDiscardable(session)) {
        return true
    }

    return hasDiscardableAnnotation(session)
}

fun FirCallableReferenceAccess.isDiscardable(session: FirSession) : Boolean {
    if (getReturnType()?.isDiscardable(session) == true) {
        return true
    }

    return hasDiscardableAnnotation(session)
}

fun FirCallableReferenceAccess.getReturnType() : ConeKotlinType? {
    return resolvedType.typeArguments.lastOrNull()?.type
}

fun FirQualifiedAccessExpression.hasDiscardableAnnotation(session: FirSession) : Boolean {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return false

    return funcSymbol.hasAnnotation(Commons.Annotations.Discardable, session)
}

fun FirQualifiedAccessExpression.hasConsumeAnnotation(session: FirSession) : Boolean {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return false
    return funcSymbol.hasConsumeAnnotation(session)
}

fun FirFunctionSymbol<*>.hasConsumeAnnotation(session: FirSession) : Boolean {
    return hasAnnotation(Commons.Annotations.Consume, session)
}

fun FirQualifiedAccessExpression.getConsumedParameters() : Set<Int> {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return setOf()

    return funcSymbol.valueParameterSymbols.asSequence()
        .withIndex()
        .filter { (_, it) ->
            it.containsAnnotation(Commons.Annotations.Consume)
        }
        .map { (i, _) -> i}
        .toSet()
}


/* Fir Common Helpers */

fun FirBasedSymbol<*>.containsAnnotation(classId: ClassId) : Boolean {
    return resolvedAnnotationClassIds.contains(classId)
}

fun FirFunctionCall.isInvoke() = calleeReference.toResolvedFunctionSymbol()?.callableId?.isInvoke() ?: false

fun FirQualifiedAccessExpression.isClassMemberOrExtension() : Boolean {
    return calleeReference.toResolvedFunctionSymbol()?.isClassMemberOrExtension() ?: false
}

fun FirFunctionSymbol<*>.isClassMemberOrExtension() : Boolean {
    return isExtension || (containingClassLookupTag() != null)
}

/* Cone Type Helpers */

fun ConeKotlinType.isDiscardable(session: FirSession) : Boolean {
    return  isUnitOrNullableUnit ||
            isBuiltInDiscardable(classId) ||
            hasDiscardableAnnotation(session) ||
            (attributes.usageObligation?.isMayUse() ?: false)
}

fun ConeKotlinType.hasDiscardableAnnotation(session: FirSession) =
    hasClassAnnotation(session, Commons.Annotations.Discardable)

fun ConeKotlinType.hasMustConsumeAnnotation(session: FirSession) =
    hasClassAnnotation(session, Commons.Annotations.MustConsume)

fun ConeKotlinType.hasClassAnnotation(session: FirSession, classId: ClassId) : Boolean{
    val regularClassSymbol = toRegularClassSymbol(session) ?: return false
    return regularClassSymbol.hasAnnotation(classId, session)
}

fun isBuiltInDiscardable(classId: ClassId?) : Boolean {
    return classId != null && Commons.BuiltInDiscardableTypes.contains(classId)
}
