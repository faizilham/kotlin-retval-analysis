package com.faizilham.kotlin.retval.fir

import com.faizilham.kotlin.retval.fir.checkers.UsageFlowChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class UsageFlowAnalysisExtension(session: FirSession) : FirAdditionalCheckersExtension(session)  {
    override val declarationCheckers = DeclCheckers

    object DeclCheckers : DeclarationCheckers() {
        override val controlFlowAnalyserCheckers = setOf(UsageFlowChecker)
    }
}