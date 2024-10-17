package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.*
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType

object UtilizationChecker :  FirControlFlowChecker(MppCheckerKind.Common)  {
    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter, context: CheckerContext) {
        if (graph.kind != ControlFlowGraph.Kind.Function || graph.declaration?.isSynthetic != false) {
            return
        }

//        if (graph.name.toString() != "retvalue") return

        println("analyze utilization ${context.containingFile?.name} ${graph.name} ${graph.kind}")

        val analyzer = Analyzer(context, graph.name == "exampleVar")
        analyzer.analyzeGraph(graph)

        val unutilizedSources : MutableSet<FirElement> = mutableSetOf()

        for (pathInfo in analyzer.finalPathInfos) {
            for (source in pathInfo.getUnutilized()) {
                collectUnutilizedValue(source, unutilizedSources)
            }
        }

        if (graph.name == "exampleVar") {
            for (entry in analyzer.data.getLambdas().entries) {
                println("${entry.key.hashCode()} ${entry.value}")
            }
            return
        }

        for (fir in unutilizedSources) {
            reporter.reportOn(fir.source, Utils.Warnings.UNCONSUMED_VALUE, context)
        }
    }

    private fun collectUnutilizedValue(src: ValueSource, collector: MutableSet<FirElement>) {
        when(src) {
            is ValueSource.FuncCall -> collector.add(src.node.fir)
            is ValueSource.Indirect -> {
                src.sources.forEach { collectUnutilizedValue(it, collector) }
            }
            is ValueSource.QualifiedAccess -> {} // Qualified access is not a true source of unutilized value

            is ValueSource.ValueParameter -> {} // TODO: handle non-annotated consuming warning?
            is ValueSource.ThisReference -> {} // TODO: idem
        }
    }

    private class Analyzer(val context: CheckerContext, val logging: Boolean = false) {
        val data = UtilizationData()
        val finalPathInfos : MutableList<PathInfo> = mutableListOf()

        fun analyzeGraph(graph: ControlFlowGraph) {
            for (node in graph.nodes) {

                analyzeNode(node)
                (node as? CFGNodeWithSubgraphs<*>)?.subGraphs?.forEach { analyzeGraph(it) }
            }
        }

        private fun analyzeNode(node: CFGNode<*>) {
            if (node.isDead) return

            val info = propagatePathInfo(node)

            when {
                node is FunctionEnterNode -> handleFunctionStart(node, info)
                node is QualifiedAccessNode -> handleQualifiedAccess(node, info)
                node is FunctionCallNode -> handleFuncCallNode(node, info)
                node is VariableDeclarationNode -> handleVariableDeclaration(node, info)
                node.isReturnNode() -> handleReturnNode(node, info)
                node.isIndirectValueSource() -> { propagateValueSource(node, info) }
                node is AnonymousFunctionExpressionNode -> addLambda(node.fir.anonymousFunction)
                node is SplitPostponedLambdasNode -> node.lambdas.forEach { addLambda(it) }
//                node is PostponedLambdaExitNode -> {
//                    println("ple ${node.previousNodes.size} ${node.fir.anonymousFunction.invocationKind}")
//                }

                else -> {}
            }

            if (node.validNextSize() == 0) {
                finalPathInfos.add(info)
            }
        }

        private fun handleFunctionStart(node: FunctionEnterNode, info: PathInfo) {
            for (valParam in node.fir.valueParameters) {
                info.addVariable(valParam.symbol, ValueSource.ValueParameter(node, valParam))
            }
        }

        private fun handleFuncCallNode(node: FunctionCallNode, info: PathInfo) {
            if (node.fir.hasConsumeAnnotation(context.session)) {
                consumeReceiver(node, info)
            }

            if (node.fir.resolvedType.hasMustConsumeAnnotation(context.session)) {
                val valueSource = ValueSource.FuncCall(node)
                data.addValueSource(node, valueSource)

                if (!node.fir.hasDiscardableAnnotation(context.session)) {
                    info.addUnutilized(valueSource)
                }
            }
        }

        private fun consumeReceiver(node: FunctionCallNode, info: PathInfo) {
            // TODO: handle this (FirThisReceiverExpression) and others?

            val receiver = node.fir.dispatchReceiver ?: node.fir.extensionReceiver ?: return
            val valueSource = data.getValueSource(receiver) ?: return

            consumeValueSource(node, info, valueSource)
        }

        private fun consumeValueSource(node: CFGNode<*>, info: PathInfo, valueSource: ValueSource, fromBranch: Boolean = false) {
            info.removeUnutilized(valueSource)

            if (valueSource is ValueSource.QualifiedAccess && !fromBranch) {
                consumeValueSource(node, info, valueSource.source)
            } else if (valueSource is ValueSource.Indirect) {
                val isBranch = (valueSource.sources.size > 1) || fromBranch
                valueSource.sources.forEach { consumeValueSource(node, info, it, isBranch) }
            } else if (valueSource is ValueSource.ValueParameter && node.owner.isLambda()) {
                val anonFunction = node.owner.declaration as? FirAnonymousFunction ?: return
                val lambdaInfo = data.getLambdaInfo(anonFunction) ?: return

                val index = anonFunction.valueParameters.indexOf(valueSource.fir)

                if (index > -1) {
                    lambdaInfo.consumedParameters.add(index)
                }
            } else if (valueSource is ValueSource.ThisReference && node.owner.isLambda()) {
                val anonFunction = node.owner.declaration as? FirAnonymousFunction ?: return
                val lambdaInfo = data.getLambdaInfo(anonFunction) ?: return

                lambdaInfo.consumingThis = true
            }
        }

        private fun addLambda(anonFunction: FirAnonymousFunction) {
            val returningConsumable = anonFunction.returnTypeRef.coneType.hasMustConsumeAnnotation(context.session)

            data.addLambdaInfo(anonFunction, LambdaInfo(consumingThis = false, returningConsumable))
            log("lambda ${anonFunction.hashCode()} ${anonFunction.receiverParameter != null} ${anonFunction.valueParameters.map { it.symbol.name }}")
        }

        private fun handleReturnNode(node: CFGNode<*>, info: PathInfo) {
            val valueSource = propagateValueSource(node, info) ?: return
            consumeValueSource(node, info, valueSource)
        }

        private fun handleVariableDeclaration(node: VariableDeclarationNode, info: PathInfo) {
            val varType = node.fir.returnTypeRef.coneType
            if (!varType.hasMustConsumeAnnotation(context.session)) return

            var valueSource = data.getValueSource(node.firstPreviousNode) ?: return

            if (valueSource is ValueSource.QualifiedAccess) {
                 valueSource = valueSource.source
            }

            info.addVariable(node.fir.symbol, valueSource)
        }

        private fun handleQualifiedAccess(node: QualifiedAccessNode, info: PathInfo) {
            val varType = node.fir.resolvedType
            if (!varType.hasMustConsumeAnnotation(context.session)) return

            if (node.fir.calleeReference is FirThisReference) {
                data.addValueSource(node, ValueSource.ThisReference(node))
                return
            }

            val symbol = node.fir.calleeReference.symbol ?: return
            val valueSource = info.getVariable(symbol) ?: return

            data.addValueSource(node, ValueSource.QualifiedAccess(node, valueSource))
        }

        // Path context

        private fun propagatePathInfo(node: CFGNode<*>) : PathInfo {
            val pathInfos = node.previousNodes.asSequence()
                                .filterNot { it.isInvalidPrev(node) }
                                .mapNotNull { data.pathInfos[it] }
                                .toList()

            val info = when(pathInfos.size) {
                0 -> PathInfo()
                1 -> if (node.firstPreviousNode.validNextSize() == 1) pathInfos[0] else pathInfos[0].copy()
                2 -> pathInfos[0].merge(pathInfos[1])
                else -> pathInfos.mergeInfos()
            }

            data.pathInfos[node] = info

            return info
        }

        // Value source

        private fun propagateValueSource(node: CFGNode<*>, info: PathInfo) : ValueSource.Indirect? {
            var hasUnutilized = false
            val valueSources = node.previousNodes.mapNotNull {
                val valSrc = if (it.isInvalidPrev(node)) null
                             else data.removeValueSource(it)

                if (valSrc != null) {
                    info.removeUnutilized(valSrc)
                    hasUnutilized = true
                }

                valSrc
            }

            if (valueSources.isNotEmpty()) {
                val newSource = flattenSingleIndirect(node, valueSources)
                data.addValueSource(node, newSource)

                if (hasUnutilized) {
                    info.addUnutilized(newSource)
                }

                return newSource
            }

            return null
        }

        private fun flattenSingleIndirect(node: CFGNode<*>, sources: List<ValueSource>): ValueSource.Indirect {
            if (sources.size == 1) {
                val source = sources[0]
                if (source is ValueSource.Indirect) {
                    return ValueSource.Indirect(node, source.sources)
                }
            }

            return ValueSource.Indirect(node, sources)
        }

        private fun log(message: Any? = null) {
            if (logging) {
                if (message == null) println()
                else println(message)
            }
        }

    }

}

class UtilizationData {
    val pathInfos : MutableMap<CFGNode<*>, PathInfo> = mutableMapOf()
    private val valueSources : MutableMap<CFGNode<*>, ValueSource> = mutableMapOf()
    private val lastFirValueNode : MutableMap<FirElement, CFGNode<*>> = mutableMapOf()
    private val consumingLambdas : MutableMap<FirAnonymousFunction, LambdaInfo> = mutableMapOf()

    fun addValueSource(node: CFGNode<*>, source: ValueSource) {
        valueSources[node] = source
        lastFirValueNode[node.fir] = node
    }

    fun getValueSource(node: CFGNode<*>) = valueSources[node]

    fun getValueSource(fir: FirElement) : ValueSource? {
        val node = lastFirValueNode[fir] ?: return null
        return valueSources[node]
    }

    fun removeValueSource(node: CFGNode<*>) = valueSources.remove(node)

    fun addLambdaInfo(func: FirAnonymousFunction, info: LambdaInfo) {
        consumingLambdas[func] = info
    }

    fun getLambdaInfo(func: FirAnonymousFunction) = consumingLambdas[func]

    fun getLambdas() = consumingLambdas
}

data class LambdaInfo(
    var consumingThis : Boolean = false,
    var returningConsumable : Boolean = false,
    val consumedParameters : MutableSet<Int> = mutableSetOf(),
)

sealed class ValueSource(val node: CFGNode<*>) {
    class FuncCall(node: CFGNode<*>) : ValueSource(node) {
        override fun equals(other: Any?): Boolean {
            return other is FuncCall && node == other.node
        }

        override fun hashCode(): Int {
            return node.hashCode()
        }

    }

    class Indirect(node: CFGNode<*>, val sources: List<ValueSource>) : ValueSource(node) {
        override fun equals(other: Any?): Boolean {
            return other is Indirect && node == other.node
        }

        override fun hashCode(): Int {
            return node.hashCode()
        }
    }

    // pseudo sources

    class QualifiedAccess(node: CFGNode<*>, val source: ValueSource) : ValueSource(node) {
        override fun equals(other: Any?): Boolean {
            return other is QualifiedAccess && node == other.node
        }

        override fun hashCode(): Int {
            return node.hashCode()
        }
    }

    class ValueParameter(node: CFGNode<*>, val fir: FirValueParameter) : ValueSource(node) {
        override fun equals(other: Any?): Boolean {
            return other is ValueParameter && node == other.node
        }

        override fun hashCode(): Int {
            return node.hashCode()
        }
    }

    class ThisReference(node: CFGNode<*>) : ValueSource(node) {
        override fun equals(other: Any?): Boolean {
            return other is ThisReference && node == other.node
        }

        override fun hashCode(): Int {
            return node.hashCode()
        }
    }
}

class PathInfo(
    private val knownVariables : MutableMap<FirBasedSymbol<*>, ValueSource> = mutableMapOf(),
    private val unutilizedValues : MutableSet<ValueSource> = mutableSetOf()
) {
    fun copy() : PathInfo {
        return PathInfo(knownVariables.toMutableMap(), unutilizedValues.toMutableSet())
    }

    // variables

    fun addVariable(variable: FirBasedSymbol<*>, valueSource: ValueSource) {
        knownVariables[variable] = valueSource
    }

    fun getVariable(variable: FirBasedSymbol<*>) = knownVariables[variable]

    // unutilized values

    fun addUnutilized(src: ValueSource.Indirect) {
        unutilizedValues.add(src)
    }

    fun addUnutilized(src: ValueSource.FuncCall) {
        unutilizedValues.add(src)
    }

    fun removeUnutilized(src: ValueSource) : Boolean {
        return unutilizedValues.remove(src)
    }

    fun getUnutilized() = unutilizedValues

    fun merge(other: PathInfo) : PathInfo {
        val unutilized = unutilizedValues.union(other.unutilizedValues).toMutableSet()

        val result = PathInfo(mutableMapOf(), unutilized)

        val commonVars = knownVariables.keys.intersect(other.knownVariables.keys)

        for (variable in commonVars) {
            val nodeValue = knownVariables[variable] ?: continue
            if (nodeValue == other.knownVariables[variable]) {
                result.knownVariables[variable] = nodeValue
            }
        }

        return result
    }
}

fun List<PathInfo>.mergeInfos() : PathInfo {
    assert(size > 1)

    var result = first()

    asSequence().drop(1).forEach {
        result = result.merge(it)
    }

    return result
}

private fun CFGNode<*>.isReturnNode(): Boolean {
    val nextIsExit = followingNodes.firstOrNull() is FunctionExitNode ||
                     followingNodes.lastOrNull() is FunctionExitNode

    return  (this is JumpNode && nextIsExit) ||
            (this is BlockExitNode && nextIsExit && owner.isLambda())
}

private fun CFGNode<*>.isIndirectValueSource(): Boolean {
    return  (this is JumpNode) ||
            (this is BlockExitNode) ||
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
