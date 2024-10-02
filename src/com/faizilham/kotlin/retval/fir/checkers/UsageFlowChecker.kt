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
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.resolvedType

object UsageFlowChecker : FirControlFlowChecker(MppCheckerKind.Common) {
    private val checkedCFGKinds = setOf(
        ControlFlowGraph.Kind.Function,
        ControlFlowGraph.Kind.AnonymousFunction,
        ControlFlowGraph.Kind.AnonymousFunctionCalledInPlace,
    )

    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter, context: CheckerContext) {
        if (graph.kind !in checkedCFGKinds || graph.declaration?.isSynthetic != false) {
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
            is UnusedSource.AtomicExpr -> {
                reporter.reportOn(src.node.fir.source, Utils.Warnings.UNUSED_VALUE, context)
            }
            is UnusedSource.FuncCall -> {
                reporter.reportOn(src.node.fir.source, Utils.Warnings.UNUSED_RETURN_VALUE , context)
            }
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
            val previousContext = getPreviousMinScopePathCtx(node, data) ?: PathContext()

            val currentContext = PathContext(previousContext.argumentScopeLevel + change)
            data.addPathContext(node, currentContext)
        }

        private fun propagateContext(node: CFGNode<*>, data: ValueUsageData) : PathContext {
            val pathContext = getPreviousMinScopePathCtx(node, data)?.copy() ?: PathContext()
            data.addPathContext(node, pathContext)

            return pathContext
        }

        private fun getPreviousMinScopePathCtx(node: CFGNode<*>, data: ValueUsageData ) : PathContext? {
            return node.previousNodes.asSequence()
                .filter { !it.isInvalidPrev(node) }
                .mapNotNull { data.getPathContext(it) }
                .fold(null) { acc: PathContext?, it ->
                    if (acc == null || it.argumentScopeLevel < acc.argumentScopeLevel) {
                        it
                    } else {
                        acc
                    }
                }
        }

        // usage changing visits
        override fun visitFunctionCallNode(node: FunctionCallNode, data: ValueUsageData) {
            val pathContext = propagateContext(node, data)
            val used =  pathContext.argumentScopeLevel > 0 ||
                    node.fir.isDiscardable() ||
                    isInvokingDiscardable(node, data)

            if (!used) {
                data.addUnused(node, UnusedSource.FuncCall(node))
            }
        }

        override fun visitLiteralExpressionNode(node: LiteralExpressionNode, data: ValueUsageData) {
            checkUnusedLiteral(node, node.fir, data)
        }

        override fun visitQualifiedAccessNode(node: QualifiedAccessNode, data: ValueUsageData) {
            checkUnusedLiteral(node, node.fir, data)
        }

        override fun visitAnonymousFunctionExpressionNode(node: AnonymousFunctionExpressionNode, data: ValueUsageData) {
            checkUnusedLiteral(node, node.fir, data)
        }

        private fun checkUnusedLiteral(node: CFGNode<*>, fir: FirExpression, data: ValueUsageData) {
            val pathContext = propagateContext(node, data)
            val used = pathContext.argumentScopeLevel > 0 ||
                       fir.resolvedType.isDiscardable()

            if (!used) {
                data.addUnused(node, UnusedSource.AtomicExpr(node))
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

        override fun visitJumpNode(node: JumpNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        override fun visitBlockExitNode(node: BlockExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        override fun visitWhenExitNode(node: WhenExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        override fun visitWhenBranchResultExitNode(node: WhenBranchResultExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        // binary operators
        override fun visitEqualityOperatorCallNode(node: EqualityOperatorCallNode, data: ValueUsageData) {
            val pathContext = propagateContext(node, data)

            if (pathContext.argumentScopeLevel > 0) return


            for (argument in node.fir.arguments) {
                data.removeUnused(argument)
            }

            data.addUnused(node, UnusedSource.FuncCall(node))
        }

        override fun visitElvisLhsExitNode(node: ElvisLhsExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        override fun visitElvisLhsIsNotNullNode(node: ElvisLhsIsNotNullNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        override fun visitElvisExitNode(node: ElvisExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        override fun visitBinaryAndExitLeftOperandNode(node: BinaryAndExitLeftOperandNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        override fun visitBinaryAndExitNode(node: BinaryAndExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        override fun visitBinaryOrExitLeftOperandNode(node: BinaryOrExitLeftOperandNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        override fun visitBinaryOrExitNode(node: BinaryOrExitNode, data: ValueUsageData) {
            propagateContext(node, data)
            propagateIndirectUnused(node, data)
        }

        private fun markFirstPreviousAsUsed(node: CFGNode<*>, data: ValueUsageData) {
            val valueNode = node.firstPreviousNode
            data.removeUnused(valueNode)
        }

        private fun propagateIndirectUnused(node: CFGNode<*>, data: ValueUsageData) {
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
    private val unusedValues : MutableMap<CFGNode<*>, UnusedSource> = mutableMapOf()
    private val lastUnusedFirNode : MutableMap<FirElement, CFGNode<*>> = mutableMapOf()

    val discardableFunctionRef: MutableSet<FunctionRef> = mutableSetOf()

    fun getPathContext(node: CFGNode<*>) : PathContext? {
        return pathContexts[node]
    }

    fun addPathContext(node: CFGNode<*>, context: PathContext) {
        pathContexts[node] = context
    }

    fun addUnused(node: CFGNode<*>, source: UnusedSource) {
        unusedValues[node] = source
        lastUnusedFirNode[node.fir] = node
    }

    fun getUnused(node: CFGNode<*>) : UnusedSource? {
        return unusedValues[node]
    }

    fun removeUnused(node: CFGNode<*>) : UnusedSource? {
        if (lastUnusedFirNode[node.fir] == node) {
            lastUnusedFirNode.remove(node.fir)
        }

        return unusedValues.remove(node)
    }

    fun removeUnused(fir: FirElement) : UnusedSource? {
        // work-around for EqualityOperatorCallNode case
        val node = lastUnusedFirNode[fir] ?: return null
        return unusedValues.remove(node)
    }

    fun getUnusedValues() = unusedValues.values
}

private data class PathContext(
    val argumentScopeLevel: Int = 0,
)

private sealed interface UnusedSource {
    class AtomicExpr(val node: CFGNode<*>) : UnusedSource {}
    class FuncCall(val node: CFGNode<*>) : UnusedSource {}
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