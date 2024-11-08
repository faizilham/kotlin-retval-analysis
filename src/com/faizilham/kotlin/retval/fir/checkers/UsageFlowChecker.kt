package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.*
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
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

//        println("analyze ${context.containingFile?.name} ${graph.name} ${graph.kind}")

        val analyzer = Analyzer(context)

        analyzer.analyzeGraph(graph)

        for (source in analyzer.data.getUnusedValues()) {
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

    private class Analyzer(val context: CheckerContext) {
        val data = ValueUsageData()

        fun analyzeGraph(graph: ControlFlowGraph) {
            for (node in graph.nodes) {
                analyzeNode(node)
                (node as? CFGNodeWithSubgraphs<*>)?.subGraphs?.forEach { analyzeGraph(it) }
            }
        }

        private fun analyzeNode(node: CFGNode<*>) {
            when {
                node.isValueConsumingContextStart() -> {
                    increaseScopeContextLevel(node, ContextType.ValueConsuming)
                }

                node.isValueConsumingContextEnd() -> {
                    decreaseScopeContextLevel(node)
                }

                node is BlockEnterNode -> {
                    increaseScopeContextLevel(node, ContextType.Block)
                }

                node is BlockExitNode -> {
                    val pathContext = decreaseScopeContextLevel(node)
                    propagateIndirectUnused(node, pathContext)
                }

                node is FunctionEnterNode -> {
                    handleFunctionEnterNode(node)
                }

                node is FunctionExitNode -> {
                    handleFunctionExitNode(node)
                }

                node is FunctionCallNode -> {
                    handleFunctionCallNode(node)
                }

                node is QualifiedAccessNode -> {
                    handleQualifiedAccessNode(node)
                }

                node.isAtomicExpression() -> {
                    checkUnusedLiteral(node, (node.fir as FirExpression))
                }

                node is VariableDeclarationNode -> {
                    handleVariableDeclarationNode(node)
                }

                node is VariableAssignmentNode -> {
                    handleVariableAssignmentNode(node)
                }

                node is EqualityOperatorCallNode -> {
                    handleEqualityOperatorCallNode(node)
                }

                node.isIndirectUnusedNodes() -> {
                    // nodes that can be a statement or an expression, and using the previous nodes as values
                    val pathContext = propagateContext(node)
                    propagateIndirectUnused(node, pathContext)
                }

                else -> propagateContext(node)
            }
        }

        // Node handlers
        private fun handleFunctionCallNode(node: FunctionCallNode) {
            val pathContext = propagateContext(node)

            val used =  pathContext.isValueConsuming() ||
                        node.fir.isDiscardable(context.session) ||
                        isInvokingDiscardable(node) ||
                        isPropagatingSameUseDiscardable(node)

            if (!used) {
                data.addUnused(node, UnusedSource.FuncCall(node))
            }
        }

        private fun isInvokingDiscardable(node: FunctionCallNode) : Boolean {
            if (!node.fir.isInvoke()) return false

            val originalSymbol =
                (node.fir.dispatchReceiver as? FirQualifiedAccessExpression)?.calleeReference?.symbol
                    ?: return false

            return originalSymbol.toFunctionRef() in data.discardableFunctionRef
        }

        private fun isPropagatingSameUseDiscardable(node: FunctionCallNode) : Boolean {
            val sameUse = node.fir.getSameUseArguments()
            if (sameUse.isEmpty()) return false

            return sameUse.all {
                when (it) {
                    is FirAnonymousFunctionExpression -> {
                        it.anonymousFunction.toFunctionRef() in data.discardableFunctionRef
                    }
                    is FirCallableReferenceAccess -> {
                        it.isDiscardable(context.session)
                    }
                    is FirQualifiedAccessExpression -> {
                        it.calleeReference.symbol?.toFunctionRef() in data.discardableFunctionRef
                    }
                    else -> false
                    // TODO: handle other nodes?
                }
            }
        }

        private fun handleVariableDeclarationNode(node: VariableDeclarationNode) {
            propagateContext(node)
            markFirstPreviousAsUsed(node)

            if (!node.fir.isVal) return

            val varType = node.fir.returnTypeRef.coneType

            if (varType.isSomeFunctionType(context.session)) {
                checkDiscardableFunctionReference(node)
            }
        }

        private fun handleVariableAssignmentNode(node: VariableAssignmentNode) {
            propagateContext(node)
            markFirstPreviousAsUsed(node)
            markReceiverAsUsed(node.fir.dispatchReceiver)
        }

        private fun checkDiscardableFunctionReference(node: VariableDeclarationNode) {
            val isDiscardableRef = node.firstPreviousNode.let {
                when (it) {
                    is AnonymousFunctionExpressionNode -> {
                        val reference = it.fir.anonymousFunction.toFunctionRef()
                        reference in data.discardableFunctionRef
                    }
                    is CallableReferenceNode -> it.fir.isDiscardable(context.session)

                    is QualifiedAccessNode -> {
                        val quaReference = it.fir.calleeReference.symbol?.toFunctionRef()
                        quaReference in data.discardableFunctionRef
                    }
                    else -> false
                    // TODO: handle other nodes?
                }
            }

            if (isDiscardableRef) {
                data.discardableFunctionRef.add(node.fir.symbol.toFunctionRef())
            }
        }

        private fun handleQualifiedAccessNode(node: QualifiedAccessNode) {
            markReceiverAsUsed(node.fir.dispatchReceiver)
            checkUnusedLiteral(node, node.fir)
        }

        private fun markReceiverAsUsed(receiver: FirExpression?) {
            if (receiver == null) return

            val originalReceiver =
                if (receiver is FirCheckedSafeCallSubject) {
                    receiver.originalReceiverRef.value
                } else {
                    receiver
                }

            data.removeUnused(originalReceiver)
        }

        private fun handleFunctionEnterNode(node: FunctionEnterNode){
            val lastContext = getPreviousMinScopePathCtx(node) ?: PathContext.defaultBlockContext

            val currentContext = PathContext(lastContext, ContextType.Block, 0)
            data.addPathContext(node, currentContext)
        }

        private fun handleFunctionExitNode(node: FunctionExitNode) {
            decreaseScopeContextLevel(node)

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

        private fun handleEqualityOperatorCallNode(node: EqualityOperatorCallNode) {
            val pathContext = propagateContext(node)

            if (pathContext.isValueConsuming()) return


            for (argument in node.fir.arguments) {
                data.removeUnused(argument)
            }

            data.addUnused(node, UnusedSource.FuncCall(node))
        }

        // Context helpers
        private fun increaseScopeContextLevel(node: CFGNode<*>, contextType: ContextType): PathContext {
            val lastContext = getPreviousMinScopePathCtx(node) ?: PathContext.defaultBlockContext

            val currentContext = PathContext(lastContext, contextType)
            data.addPathContext(node, currentContext)
            return currentContext
        }

        private fun decreaseScopeContextLevel(node: CFGNode<*>): PathContext {
            val lastContext = getPreviousMinScopePathCtx(node) ?: PathContext.defaultBlockContext
            val parentContext = lastContext.previousContext ?: PathContext.defaultBlockContext
            data.addPathContext(node, parentContext)
            return parentContext
        }

        private fun propagateContext(node: CFGNode<*>) : PathContext {
            val pathContext = getPreviousMinScopePathCtx(node) ?: PathContext(null, ContextType.Block)
            data.addPathContext(node, pathContext)

            return pathContext
        }

        private fun getPreviousMinScopePathCtx(node: CFGNode<*> ) : PathContext? {
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
        private fun checkUnusedLiteral(node: CFGNode<*>, fir: FirExpression) {
            val pathContext = propagateContext(node)
            val used = pathContext.isValueConsuming() ||
                    fir.resolvedType.isDiscardable(context.session)

            if (!used) {
                data.addUnused(node, UnusedSource.AtomicExpr(node))
            }
        }

        private fun propagateIndirectUnused(node: CFGNode<*>, currentContext: PathContext) {
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

        private fun markFirstPreviousAsUsed(node: CFGNode<*>) : UnusedSource? {
            val valueNode = node.firstPreviousNode
            return data.removeUnused(valueNode)
        }
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
            (this is BinaryOrExitNode) ||
            (this is ExitSafeCallNode)
}

// Other Helpers
private fun FirFunctionCall.getSameUseArguments() : List<FirExpression> {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return emptyList()

    return funcSymbol.valueParameterSymbols.asSequence()
        .withIndex()
        .filter { (_, it) ->
            it.containsAnnotation(Utils.Constants.SameUseClassId)
        }
        .mapNotNull { (i, _) -> argumentList.arguments.getOrNull(i)}
        .toList()
}

//data class IntList(var value : Int, var next: IntList?)
//
//
//fun testlist() {
//    val l = IntList(1, IntList(2, IntList(3, null)))
//    val l2 = l.next
//    val l3 = l.next?.next
//
//    l.next?.next?.value = 2
//}