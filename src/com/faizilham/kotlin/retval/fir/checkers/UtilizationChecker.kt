package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.checkers.analysis.FuncAliasAnalysis
import com.faizilham.kotlin.retval.fir.checkers.analysis.UtilizationAnalysis
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraph

object UtilizationChecker :  FirControlFlowChecker(MppCheckerKind.Common) {
    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter, context: CheckerContext) {
        if (graph.kind != ControlFlowGraph.Kind.Function || graph.declaration?.isSynthetic != false) {
            return
        }

        val logging = context.containingFile?.name == "highorder.kt" && graph.name == "effectMismatch"
//        val logging = false

        val funcAnalyzer = FuncAliasAnalysis(context, logging)
        funcAnalyzer.analyzeGraph(graph)

        val utilAnalyzer = UtilizationAnalysis(context, funcAnalyzer.data, logging)
        utilAnalyzer.analyzeGraph(graph)
        utilAnalyzer.report(reporter)
    }
}
