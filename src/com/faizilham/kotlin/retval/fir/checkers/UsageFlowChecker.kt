package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.Utils
import com.faizilham.kotlin.retval.fir.containsAnnotation
import com.faizilham.kotlin.retval.fir.isDiscardable
import com.faizilham.kotlin.retval.fir.isInvoke
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
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
        analyzeGraph(graph, valueUsageData)

        for (source in valueUsageData.getUnusedValues()) {
            reportUnused(source, reporter, context)
        }
    }

    private fun analyzeGraph(graph: ControlFlowGraph, data: ValueUsageData) {
        for (node in graph.nodes) {
            analyzeNode(node, data)
            (node as? CFGNodeWithSubgraphs<*>)?.subGraphs?.forEach { analyzeGraph(it, data) }
        }
    }

    private fun analyzeNode(node: CFGNode<*>, data: ValueUsageData) {
        when {
            node.isValueConsumingContextStart() -> {
                increaseScopeContextLevel(node, data, ContextType.ValueConsuming)
            }

            node.isValueConsumingContextEnd() -> {
                decreaseScopeContextLevel(node, data)
            }

            node is BlockEnterNode -> {
                increaseScopeContextLevel(node, data, ContextType.Block)
            }

            node is BlockExitNode -> {
                val pathContext = decreaseScopeContextLevel(node, data)
                propagateIndirectUnused(node, data, pathContext)
            }

            node is FunctionEnterNode -> {
                handleFunctionEnterNode(node, data)
            }

            node is FunctionExitNode -> {
                handleFunctionExitNode(node, data)
            }

            node is FunctionCallNode -> {
                handleFunctionCallNode(node, data)
            }

            node.isAtomicExpression() -> {
                checkUnusedLiteral(node, (node.fir as FirExpression), data)
            }

            node is VariableDeclarationNode -> {
                handleVariableDeclarationNode(node, data)
            }

            node is VariableAssignmentNode -> {
                propagateContext(node, data)
                markFirstPreviousAsUsed(node, data)
            }

            node is EqualityOperatorCallNode -> {
                handleEqualityOperatorCallNode(node, data)
            }

            node.isIndirectUnusedNodes() -> {
                // nodes that can be a statement or an expression, and using the previous nodes as values
                val pathContext = propagateContext(node, data)
                propagateIndirectUnused(node, data, pathContext)
            }

            else -> propagateContext(node, data)
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

    // Node handlers
    private fun handleFunctionCallNode(node: FunctionCallNode, data: ValueUsageData) {
        val pathContext = propagateContext(node, data)
        val used =  pathContext.isValueConsuming() ||
                    node.fir.isDiscardable() ||
                    isInvokingDiscardable(node, data) ||
                    isPropagatingSameUseDiscardable(node, data)

        if (!used) {
            data.addUnused(node, UnusedSource.FuncCall(node))
        }
    }

    private fun isInvokingDiscardable(node: FunctionCallNode, data: ValueUsageData) : Boolean {
        if (!node.fir.isInvoke()) return false

        val originalSymbol =
            (node.fir.dispatchReceiver as? FirQualifiedAccessExpression)?.calleeReference?.symbol
                ?: return false

        return originalSymbol.toFunctionRef() in data.discardableFunctionRef
    }

    private fun isPropagatingSameUseDiscardable(node: FunctionCallNode, data: ValueUsageData) : Boolean {
        val sameUseIndexes = node.fir.getSameUseParameterIndexes()
        if (sameUseIndexes.isEmpty()) return false

        val arguments = node.fir.argumentList.arguments
        val sameUse = sameUseIndexes.map { arguments[it] }

        return sameUse.all {
            when (it) {
                is FirAnonymousFunctionExpression -> {
                    it.anonymousFunction.toFunctionRef() in data.discardableFunctionRef
                }
                is FirCallableReferenceAccess -> {
                    it.isDiscardable()
                }
                is FirQualifiedAccessExpression -> {
                    it.calleeReference.symbol?.toFunctionRef() in data.discardableFunctionRef
                }
                else -> false
            }
        }
    }


    private fun handleVariableDeclarationNode(node: VariableDeclarationNode, data: ValueUsageData) {
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

    private fun handleFunctionEnterNode(node: FunctionEnterNode, data: ValueUsageData){
        val lastContext = getPreviousMinScopePathCtx(node, data) ?: PathContext.defaultBlockContext

        val currentContext = PathContext(lastContext, ContextType.Block, 0)
        data.addPathContext(node, currentContext)
    }

    private fun handleFunctionExitNode(node: FunctionExitNode, data: ValueUsageData) {
        decreaseScopeContextLevel(node, data)

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
                ?.let{
                    data.discardableFunctionRef.add(it)
                }
        }
    }

    private fun handleEqualityOperatorCallNode(node: EqualityOperatorCallNode, data: ValueUsageData) {
        val pathContext = propagateContext(node, data)

        if (pathContext.isValueConsuming()) return


        for (argument in node.fir.arguments) {
            data.removeUnused(argument)
        }

        data.addUnused(node, UnusedSource.FuncCall(node))
    }

    // Context helpers
    private fun increaseScopeContextLevel(node: CFGNode<*>, data: ValueUsageData, contextType: ContextType): PathContext {
        val lastContext = getPreviousMinScopePathCtx(node, data) ?: PathContext.defaultBlockContext

        val currentContext = PathContext(lastContext, contextType)
        data.addPathContext(node, currentContext)
        return currentContext
    }

    private fun decreaseScopeContextLevel(node: CFGNode<*>, data: ValueUsageData): PathContext {
        val lastContext = getPreviousMinScopePathCtx(node, data) ?: PathContext.defaultBlockContext
        val parentContext = lastContext.previousContext ?: PathContext.defaultBlockContext
        data.addPathContext(node, parentContext)
        return parentContext
    }

    private fun propagateContext(node: CFGNode<*>, data: ValueUsageData) : PathContext {
        val pathContext = getPreviousMinScopePathCtx(node, data) ?: PathContext(null, ContextType.Block)
        data.addPathContext(node, pathContext)

        return pathContext
    }

    private fun getPreviousMinScopePathCtx(node: CFGNode<*>, data: ValueUsageData ) : PathContext? {
        return node.previousNodes.asSequence()
            .filter { !it.isInvalidPrev(node) }
            .mapNotNull { data.getPathContext(it) }
            .fold(null) { acc: PathContext?, it ->
                if (acc == null || it.contextDepth < acc.contextDepth) {
                    it
                } else {
                    acc
                }
            }
    }

    // Unused Value Helpers
    private fun checkUnusedLiteral(node: CFGNode<*>, fir: FirExpression, data: ValueUsageData) {
        val pathContext = propagateContext(node, data)
        val used = pathContext.isValueConsuming() ||
                fir.resolvedType.isDiscardable()

        if (!used) {
            data.addUnused(node, UnusedSource.AtomicExpr(node))
        }
    }

    private fun propagateIndirectUnused(node: CFGNode<*>, data: ValueUsageData, currentContext: PathContext) {
        val unusedSources = node.previousNodes.mapNotNull {
            if (it.isInvalidPrev(node)) null
            else data.removeUnused(it)
        }

        if (unusedSources.isNotEmpty() && !currentContext.isValueConsuming()) {
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

    private fun markFirstPreviousAsUsed(node: CFGNode<*>, data: ValueUsageData) {
        val valueNode = node.firstPreviousNode
        data.removeUnused(valueNode)
    }
}

// Node Type Classifier Helpers
private fun CFGNode<*>.isValueConsumingContextStart() : Boolean {
    return  (this is FunctionCallArgumentsEnterNode) ||
            (this is WhenBranchConditionEnterNode) ||
            (this is LoopConditionEnterNode)
}

private fun CFGNode<*>.isValueConsumingContextEnd() : Boolean {
    return  (this is FunctionCallArgumentsExitNode) ||
            (this is WhenBranchConditionExitNode) ||
            (this is LoopConditionExitNode)
}

private fun CFGNode<*>.isAtomicExpression(): Boolean {
    return  (this is LiteralExpressionNode) ||
            (this is QualifiedAccessNode) ||
            (this is AnonymousFunctionExpressionNode)
}

private fun CFGNode<*>.isIndirectUnusedNodes(): Boolean {
    return  (this is JumpNode) ||
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

// Other Helpers
private fun FirFunctionCall.getSameUseParameterIndexes() : List<Int> {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return emptyList()
    return funcSymbol.valueParameterSymbols.asSequence()
        .withIndex()
        .filter { (_, it) ->
            it.containsAnnotation(Utils.Constants.SameUseClassId)
        }
        .map { (i, _) -> i}
        .toList()
}

private fun FirDeclaration.toFunctionRef() : FunctionRef = FunctionRef.Lambda(this)
private fun FirBasedSymbol<*>.toFunctionRef() : FunctionRef = FunctionRef.Identifier(this)
private fun CFGNode<*>.isInvalidPrev(current: CFGNode<*>) = isDead || edgeTo(current).kind.isBack

private fun ControlFlowGraph.isLambda() =
    kind == ControlFlowGraph.Kind.AnonymousFunction ||
    kind == ControlFlowGraph.Kind.AnonymousFunctionCalledInPlace