package com.faizilham.kotlin.retval.fir.checkers.commons

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.warning0
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression

object Commons {
    object Annotations {
        /* Annotation class ids */
        val PACKAGE_FQN = FqName("com.faizilham.kotlin.retval.annotations")
        val Discardable = ClassId(PACKAGE_FQN, Name.identifier("Discardable"))
        val MustConsume = ClassId(PACKAGE_FQN, Name.identifier("MustConsume"))
        val Consume = ClassId(PACKAGE_FQN, Name.identifier("Consume"))
        val SameUse = ClassId(PACKAGE_FQN, Name.identifier("SameUse"))

        val UEffect = ClassId(PACKAGE_FQN, Name.identifier("UEffect"))
        val UETarget : Map<String, Int> = mapOf(
            "THIS" to -1,
            "FV" to -2
        )

        val MayUse = ClassId(PACKAGE_FQN, Name.identifier("MayUse"))
        val AnyUse = ClassId(PACKAGE_FQN, Name.identifier("AnyUse"))
    }

    val BuiltInDiscardableTypes: Set<ClassId> = setOf(
        ClassId.fromString("kotlin/contracts/CallsInPlace")
    )

    object Warnings {
        /** Unused return value warning factory */
        val UNUSED_RETURN_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
        val UNUSED_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
        val UNCONSUMED_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
    }

    object Errors {
        val SAME_USE_INVALID_TARGET: KtDiagnosticFactory0 by error0<KtExpression>()
        val SAME_USE_NOT_A_FUNCTION: KtDiagnosticFactory0 by error0<KtExpression>()
        val SAME_USE_MISMATCH_RETURN_TYPE: KtDiagnosticFactory0 by error0<KtExpression>()
        val CONSUME_NOT_MEMBER_OR_EXT: KtDiagnosticFactory0 by error0<KtExpression>()
    }
}