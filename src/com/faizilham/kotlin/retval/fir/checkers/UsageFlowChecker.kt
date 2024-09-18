package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.Utils
import com.faizilham.kotlin.retval.fir.isDiscardable
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*

object UsageFlowChecker : FirControlFlowChecker(MppCheckerKind.Common) {
    private val checkedCFGKinds = setOf(
        ControlFlowGraph.Kind.Function,
        ControlFlowGraph.Kind.AnonymousFunction,
        ControlFlowGraph.Kind.AnonymousFunctionCalledInPlace,
    )

    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter, context: CheckerContext) {
        if (graph.kind !in checkedCFGKinds) {
            return
        }

//        println("analyze ${graph.name} ${graph.kind}")

        val valueUsageData = ValueUsageData()
        graph.traverse(UsageCheckerVisitor(), valueUsageData)

        for (source in valueUsageData.unusedValues.values) {
            reportUnused(source, reporter, context)
        }
    }

    private fun reportUnused(src: UnusedSource, reporter: DiagnosticReporter, context: CheckerContext) {
        when(src) {
            is UnusedSource.Self -> reporter.reportOn(src.node.fir.source, Utils.Warnings.UNUSED_RETURN_VALUE, context)
            is UnusedSource.Indirect -> src.sources.forEach { reportUnused(it, reporter, context) }
        }
    }

    private class UsageCheckerVisitor : ControlFlowGraphVisitor<Unit, ValueUsageData>() {
        override fun visitNode(node: CFGNode<*>, data: ValueUsageData) {
            propagateContext(node, data)
        }

        // context changing visits
        override fun visitFunctionCallArgumentsEnterNode(node: FunctionCallArgumentsEnterNode, data: ValueUsageData) {
            updateArgumentScopeLevel(node, data, 1)
        }

        override fun visitFunctionCallArgumentsExitNode(node: FunctionCallArgumentsExitNode, data: ValueUsageData) {
            updateArgumentScopeLevel(node, data, -1)
        }

        override fun visitWhenBranchConditionEnterNode(node: WhenBranchConditionEnterNode, data: ValueUsageData) {
            updateArgumentScopeLevel(node, data, 1)
        }

        override fun visitWhenBranchConditionExitNode(node: WhenBranchConditionExitNode, data: ValueUsageData) {
            updateArgumentScopeLevel(node, data, -1)
        }

        override fun visitLoopConditionEnterNode(node: LoopConditionEnterNode, data: ValueUsageData) {
            updateArgumentScopeLevel(node, data, 1)
        }

        override fun visitLoopConditionExitNode(node: LoopConditionExitNode, data: ValueUsageData) {
            updateArgumentScopeLevel(node, data, -1)
        }

        private fun updateArgumentScopeLevel(node: CFGNode<*>, data: ValueUsageData, change: Int) {
            val currentContext = data.getPathContext(node)
            val newContext = PathContext(currentContext.argumentScopeLevel + change)

            propagateContext(node, data, newContext)
        }

        private fun propagateContext(node: CFGNode<*>, data: ValueUsageData, newContext: PathContext? = null) : PathContext {
            val pathContext = newContext ?: data.getPathContext(node)

            for (next in node.followingNodes) {
                if (next.isInvalidNext(node)) continue
                data.addPathContext(next, pathContext)
            }

            return pathContext
        }

        // usage changing visits

        override fun visitFunctionCallNode(node: FunctionCallNode, data: ValueUsageData) {
            val pathContext = propagateContext(node, data)
            val used = pathContext.argumentScopeLevel > 0 || node.fir.isDiscardable()

            if (!used) {
                data.unusedValues[node] = UnusedSource.Self(node)
            }
        }

        override fun visitVariableDeclarationNode(node: VariableDeclarationNode, data: ValueUsageData) {
            propagateContext(node, data)
            markFirstPreviousAsUsed(node, data)
        }

        override fun visitVariableAssignmentNode(node: VariableAssignmentNode, data: ValueUsageData) {
            propagateContext(node, data)
            markFirstPreviousAsUsed(node, data)
        }

        override fun visitJumpNode(node: JumpNode, data: ValueUsageData) {
            propagateContext(node, data)

            if (node.isReturn()) {
                markFirstPreviousAsUsed(node, data)
            }
        }

        override fun visitBlockExitNode(node: BlockExitNode, data: ValueUsageData) {
            propagateContext(node, data)

            if (node.isReturn()) {
                markFirstPreviousAsUsed(node, data)
            } else {
                propagateUnused(node, data)
            }
        }

        override fun visitWhenExitNode(node: WhenExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        override fun visitWhenBranchResultExitNode(node: WhenBranchResultExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        private fun markFirstPreviousAsUsed(node: CFGNode<*>, data: ValueUsageData) {
            val valueNode = node.firstPreviousNode
            if (valueNode in data.unusedValues) {
                data.unusedValues.remove(valueNode)
            }
        }

        private fun propagateUnused(node: CFGNode<*>, data: ValueUsageData) {
            val unusedSources = node.previousNodes.mapNotNull {
                if (it.isInvalidPrev(node)) null
                else data.unusedValues.remove(it)
            }

            if (unusedSources.isNotEmpty()) {
                data.unusedValues[node] = flattenSingleIndirect(unusedSources)
            }
        }

        private fun flattenSingleIndirect(sources: List<UnusedSource>): UnusedSource.Indirect {
            if (sources.size == 1) {
                val source = sources[0]
                if (source is UnusedSource.Indirect) {
                    return UnusedSource.Indirect(source.sources)
                }
            }

            return UnusedSource.Indirect(sources)
        }
    }
}

private class ValueUsageData {
    val pathContexts : MutableMap<CFGNode<*>, PathContext> = mutableMapOf()
    val unusedValues : MutableMap<CFGNode<*>, UnusedSource> = mutableMapOf()

    fun getPathContext(node: CFGNode<*>) : PathContext {
        var context = pathContexts[node]

        if (context == null) {
            context = PathContext()
            pathContexts[node] = context
        }

        return context
    }

    fun addPathContext(node: CFGNode<*>, context: PathContext) {
        pathContexts[node] = context
    }
}

private data class PathContext(
    val argumentScopeLevel: Int = 0,
)

private sealed interface UnusedSource {
    class Self(val node: CFGNode<*>) : UnusedSource {}
    class Indirect(val sources: List<UnusedSource>) : UnusedSource {}
}

private fun CFGNode<*>.isInvalidNext(current: CFGNode<*>) = isDead || edgeFrom(current).kind.isBack
private fun CFGNode<*>.isInvalidPrev(current: CFGNode<*>) = isDead || edgeTo(current).kind.isBack

private fun JumpNode.isReturn() =
    (followingNodes[0].isDead && followingNodes[1] is FunctionExitNode) ||
    (followingNodes[0] is FunctionExitNode && followingNodes[1].isDead)

private fun BlockExitNode.isReturn() =
    owner.isLambda() && followingNodes[0] is FunctionExitNode

private fun ControlFlowGraph.isLambda() =
    kind == ControlFlowGraph.Kind.AnonymousFunction ||
    kind == ControlFlowGraph.Kind.AnonymousFunctionCalledInPlace