package com.faizilham.kotlin.retval.fir.checkers.commons

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.warning0
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

        val BuiltInDiscardable: Set<ClassId> = setOf(
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
        val SAME_USE_INVALID_TARGET: KtDiagnosticFactory0 by error0<KtExpression>()
        val SAME_USE_NOT_A_FUNCTION: KtDiagnosticFactory0 by error0<KtExpression>()
        val SAME_USE_MISMATCH_RETURN_TYPE: KtDiagnosticFactory0 by error0<KtExpression>()
        val CONSUME_NOT_MEMBER_OR_EXT: KtDiagnosticFactory0 by error0<KtExpression>()
    }
}