package com.faizilham.kotlin.retval.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.warning0
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression

object Utils {
    object Constants {
        /** Annotation class ids */

        private val PACKAGE_FQN = FqName("com.faizilham.kotlin.retval")
        val DiscardableClassId = ClassId(PACKAGE_FQN, Name.identifier("Discardable"))
        val MayUseClassId = ClassId(PACKAGE_FQN, Name.identifier("MayUse"))
        val AnyUseClassId = ClassId(PACKAGE_FQN, Name.identifier("AnyUse"))
    }

    object Warnings {
        /** Unused return value warning factory */
        val UNUSED_RETURN_VALUE: KtDiagnosticFactory0 by warning0<KtExpression>()
    }
}

