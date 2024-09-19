package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.Utils
import com.faizilham.kotlin.retval.fir.isDiscardable
import com.faizilham.kotlin.retval.fir.isInvoke
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

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

        println("analyze ${graph.name} ${graph.kind}")

        val valueUsageData = ValueUsageData()
        graph.traverse(UsageCheckerVisitor(), valueUsageData)

        for (source in valueUsageData.getUnusedValues()) {
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
            val used =  pathContext.argumentScopeLevel > 0 ||
                        node.fir.isDiscardable() ||
                        isInvokingDiscardable(node, data)

            if (!used) {
                data.addUnused(node, UnusedSource.Self(node))
            }
        }

        private fun isInvokingDiscardable(node: FunctionCallNode, data: ValueUsageData) : Boolean {
            if (!node.fir.isInvoke()) return false

            val originalSymbol =
                (node.fir.dispatchReceiver as? FirQualifiedAccessExpression)?.calleeReference?.symbol
                ?: return false

            return originalSymbol.toFunctionRef() in data.discardableFunctionRef
        }

        override fun visitVariableDeclarationNode(node: VariableDeclarationNode, data: ValueUsageData) {
            propagateContext(node, data)
            markFirstPreviousAsUsed(node, data)

            if (!node.fir.isVal) return
            val symbol = node.fir.symbol

            val isDiscardableRef = node.firstPreviousNode.let {
                when (it) {
                    is AnonymousFunctionExpressionNode -> {
                        val reference = it.fir.anonymousFunction.toFunctionRef()
                        reference in data.discardableFunctionRef
                    }
                    is CallableReferenceNode -> it.fir.isDiscardable()

                    is QualifiedAccessNode -> {
                        val quaReference = it.fir.calleeReference.symbol?.toFunctionRef()
                        quaReference in data.discardableFunctionRef
                    }
                    else -> false
                }
            }

            if (isDiscardableRef) {
                data.discardableFunctionRef.add(symbol.toFunctionRef())
            }
        }

        override fun visitVariableAssignmentNode(node: VariableAssignmentNode, data: ValueUsageData) {
            propagateContext(node, data)
            markFirstPreviousAsUsed(node, data)
        }

        override fun visitJumpNode(node: JumpNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        override fun visitBlockExitNode(node: BlockExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        override fun visitFunctionExitNode(node: FunctionExitNode, data: ValueUsageData) {
            propagateContext(node, data)

            val isLambda = node.owner.isLambda()
            var mustUseReturnValues = 0

            fun removeUnusedValue(node: CFGNode<*>) {
                if (data.removeUnused(node) != null) mustUseReturnValues += 1
            }

            for (prev in node.previousNodes) {
                if (prev.isInvalidPrev(node)) continue

                when (prev) {
                    is JumpNode -> removeUnusedValue(prev)
                    is BlockExitNode -> if (isLambda) removeUnusedValue(prev)
                    else -> {}
                }
            }

            if (isLambda && mustUseReturnValues == 0) {
                node.owner.declaration
                    ?.toFunctionRef()
                    ?.let{ data.discardableFunctionRef.add(it) }
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

        // binary operators
        override fun visitEqualityOperatorCallNode(node: EqualityOperatorCallNode, data: ValueUsageData) {
            val pathContext = propagateContext(node, data)

            if (pathContext.argumentScopeLevel > 0) return


            for (argument in node.fir.arguments) {
                data.removeUnused(argument)
            }

            data.addUnused(node, UnusedSource.Self(node))
        }

        override fun visitElvisLhsExitNode(node: ElvisLhsExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        override fun visitElvisLhsIsNotNullNode(node: ElvisLhsIsNotNullNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        override fun visitElvisExitNode(node: ElvisExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        override fun visitBinaryAndExitLeftOperandNode(node: BinaryAndExitLeftOperandNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        override fun visitBinaryAndExitNode(node: BinaryAndExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        override fun visitBinaryOrExitLeftOperandNode(node: BinaryOrExitLeftOperandNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        override fun visitBinaryOrExitNode(node: BinaryOrExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateUnused(node, data)
        }

        private fun markFirstPreviousAsUsed(node: CFGNode<*>, data: ValueUsageData) {
            val valueNode = node.firstPreviousNode
            data.removeUnused(valueNode)
        }

        private fun propagateUnused(node: CFGNode<*>, data: ValueUsageData) {
            val unusedSources = node.previousNodes.mapNotNull {
                if (it.isInvalidPrev(node)) null
                else data.removeUnused(it)
            }

            if (unusedSources.isNotEmpty()) {
                data.addUnused(node, flattenSingleIndirect(node, unusedSources))
            }
        }

        private fun flattenSingleIndirect(node: CFGNode<*>, sources: List<UnusedSource>): UnusedSource.Indirect {
            if (sources.size == 1) {
                val source = sources[0]
                if (source is UnusedSource.Indirect) {
                    return UnusedSource.Indirect(node, source.sources)
                }
            }

            return UnusedSource.Indirect(node, sources)
        }
    }
}

private class ValueUsageData {
    private val pathContexts : MutableMap<CFGNode<*>, PathContext> = mutableMapOf()
    private val unusedValues : MutableMap<FirElement, UnusedSource> = mutableMapOf()
    val discardableFunctionRef: MutableSet<FunctionRef> = mutableSetOf()
    // using FirElement as key for EqualityOperatorCallNode case work-around

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

    fun addUnused(node: CFGNode<*>, source: UnusedSource) {
        unusedValues[node.fir] = source
    }

    fun getUnused(node: CFGNode<*>) : UnusedSource? {
        return unusedValues[node.fir]
    }

    fun removeUnused(node: CFGNode<*>) = unusedValues.remove(node.fir)

    fun removeUnused(fir: FirElement) = unusedValues.remove(fir)

    fun getUnusedValues() = unusedValues.values
}

private data class PathContext(
    val argumentScopeLevel: Int = 0,
)

private sealed interface UnusedSource {
    class Self(val node: CFGNode<*>) : UnusedSource {}
    class Indirect(val node: CFGNode<*>, val sources: List<UnusedSource>) : UnusedSource {}
}

private sealed interface FunctionRef {
    class Lambda(val declaration: FirDeclaration) : FunctionRef {
        override fun equals(other: Any?): Boolean {
            return other is Lambda && declaration == other.declaration
        }

        override fun hashCode(): Int {
            return declaration.hashCode()
        }
    }

    class Identifier(val symbol: FirBasedSymbol<*>) : FunctionRef {
        override fun equals(other: Any?): Boolean {
            return other is Identifier && symbol == other.symbol
        }

        override fun hashCode(): Int {
            return symbol.hashCode()
        }
    }
}

private fun FirDeclaration.toFunctionRef() : FunctionRef = FunctionRef.Lambda(this)
private fun FirBasedSymbol<*>.toFunctionRef() : FunctionRef = FunctionRef.Identifier(this)

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