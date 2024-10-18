package com.faizilham.kotlin.retval.fir.checkers

import com.faizilham.kotlin.retval.fir.*
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.utils.isSynthetic
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.resolvedType

object UtilizationChecker :  FirControlFlowChecker(MppCheckerKind.Common)  {
    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter, context: CheckerContext) {
        if (graph.kind != ControlFlowGraph.Kind.Function || graph.declaration?.isSynthetic != false) {
            return
        }

        val analyzer = Analyzer(context, logging = true)

        analyzer.log("analyze utilization ${context.containingFile?.name} ${graph.name} ${graph.kind}")

        analyzer.analyzeGraph(graph)

        val unutilizedSources : MutableSet<FirElement> = mutableSetOf()

        for (pathInfo in analyzer.finalPathInfos) {
            for (source in pathInfo.getUnutilized()) {
                collectUnutilizedValue(source, unutilizedSources)
            }
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
            info.clearUnutilized() // NOTE: each function should only care about its internal unutilized TODO: other way?

            for (valParam in node.fir.valueParameters) {
                info.setVarValue(valParam.symbol, ValueSource.ValueParameter(node, valParam))
                data.setVarOwner(valParam.symbol, node.owner)
            }
        }

        private fun handleFuncCallNode(node: FunctionCallNode, info: PathInfo) {
            val funcInfo = getFunctionInfo(node.fir, info) ?: return

            if (funcInfo.consumingThis) {
                consumeReceiver(node, info)
            }

            if (funcInfo.consumedParameters.isNotEmpty()) {
                consumeParameters(node, info, funcInfo)
            }

            if (funcInfo.consumedFreeVariables.isNotEmpty()) {
                consumeFreeVariables(node, info, funcInfo)
            }

            if (funcInfo.returningConsumable) {
                val valueSource = ValueSource.FuncCall(node)
                data.addValueSource(node, valueSource)

                if (!funcInfo.returnIsConsumed) {
                    info.addUnutilized(valueSource)
                }
            }
        }

        private fun getFunctionInfo(fir: FirFunctionCall, info: PathInfo) : FunctionInfo? {
            if (fir.isInvoke()) return resolveInvokeFunctionInfo(fir, info)

            return FunctionInfo(
                isLambda = false,
                returningConsumable = fir.resolvedType.hasMustConsumeAnnotation(context.session),
                returnIsConsumed = fir.hasDiscardableAnnotation(context.session),
                consumingThis = fir.hasConsumeAnnotation(context.session),
                consumedParameters = fir.getConsumedParameters()
            )
        }

        private fun resolveInvokeFunctionInfo(fir: FirFunctionCall, info: PathInfo) : FunctionInfo? {
            val originalSymbol =
                (fir.dispatchReceiver as? FirQualifiedAccessExpression)?.calleeReference?.symbol
                    ?: return null

            var funcInfo = info.getVarValue(originalSymbol)?.funcRef ?: return null

            // NOTE: invoking extension function causes the context object to be regarded as first argument
            //       the dispatchReceiver is no longer the context object, but the function reference
            if (funcInfo.consumingThis) {
                funcInfo = funcInfo.insertThisIntoFirstParameter()
            }

            return funcInfo
        }

        private fun consumeReceiver(node: FunctionCallNode, info: PathInfo) {
            val receiver = node.fir.dispatchReceiver ?: node.fir.extensionReceiver ?: return

            var valueSource = data.getValueSource(receiver)

            if (valueSource == null) {
                if (receiver is FirThisReceiverExpression) {
                    valueSource = ValueSource.ThisReference(node)
                } else {
                    return
                }
            }

            consumeValueSource(node, info, valueSource)
        }

        private fun consumeParameters(node: FunctionCallNode, info: PathInfo, funcInfo: FunctionInfo) {
            node.fir.argumentList.arguments.forEachIndexed { i, arg ->
                if ((i !in funcInfo.consumedParameters) ||
                    (!arg.resolvedType.hasMustConsumeAnnotation(context.session))) {
                    return@forEachIndexed
                }

                val valueSource = data.getValueSource(arg) ?: return@forEachIndexed

                consumeValueSource(node, info, valueSource)
            }
        }

        private fun consumeFreeVariables(node: FunctionCallNode, info: PathInfo, funcInfo: FunctionInfo) {
            funcInfo.consumedFreeVariables.forEach {
                val currentKnownValue = info.getVarValue(it)?.utilVal ?: return@forEach

                // NOTE: using dummy for forcing indirect consumption, in case it's called in lambda
                val dummyQualifiedAccess = ValueSource.QualifiedAccess(node, it, currentKnownValue)

                consumeValueSource(node, info, dummyQualifiedAccess)
            }
        }

        // TODO: refactor this
        private fun consumeValueSource(consumerNode: CFGNode<*>, info: PathInfo, valueSource: ValueSource, fromBranch: Boolean = false) {
            info.removeUnutilized(valueSource)

            val consumingInLambda = consumerNode.owner.isLambda()

            if (valueSource is ValueSource.QualifiedAccess && !fromBranch) {
                // TODO: find other way to know if symbol is free var, instead of tracking with data.variableOwner
                if (consumingInLambda && consumerNode.owner != data.getVarOwner(valueSource.symbol)) {
                    val (lambdaInfo, _) = getLambda(consumerNode.owner.declaration) ?: return
                    lambdaInfo.consumedFreeVariables.add(valueSource.symbol)
                } else {
                    consumeValueSource(consumerNode, info, valueSource.source)
                }
            } else if (valueSource is ValueSource.Indirect) {
                val isBranch = (valueSource.sources.size > 1) || fromBranch
                valueSource.sources.forEach { consumeValueSource(consumerNode, info, it, isBranch) }
            } else if (valueSource is ValueSource.ValueParameter && consumingInLambda) {
                val (lambdaInfo, anonFunction) = getLambda(consumerNode.owner.declaration) ?: return
                val paramIndex = anonFunction.valueParameters.indexOf(valueSource.fir)

                if (paramIndex > -1) {
                    lambdaInfo.consumedParameters.add(paramIndex)
                }
            } else if (valueSource is ValueSource.ThisReference && consumingInLambda) {
                val (lambdaInfo, _) = getLambda(consumerNode.owner.declaration) ?: return

                lambdaInfo.consumingThis = true
            }
        }

        private fun getLambda(declaration: FirDeclaration?) : Pair<FunctionInfo, FirAnonymousFunction>? {
            val anonFunction = declaration as? FirAnonymousFunction ?: return null
            val lambdaInfo = data.getLambdaInfo(anonFunction) ?: return null

            return Pair(lambdaInfo, anonFunction)
        }

        private fun addLambda(anonFunction: FirAnonymousFunction) {
            val returningConsumable = anonFunction.returnTypeRef.coneType.hasMustConsumeAnnotation(context.session)

            data.addLambdaInfo(anonFunction, FunctionInfo(true, returningConsumable))
        }

        private fun handleReturnNode(node: CFGNode<*>, info: PathInfo) {
            val valueSource = propagateValueSource(node, info) ?: return
            consumeValueSource(node, info, valueSource)
        }

        private fun handleVariableDeclaration(node: VariableDeclarationNode, info: PathInfo) {
            data.setVarOwner(node.fir.symbol, node.owner)

            val varType = node.fir.returnTypeRef.coneType

            if (varType.isSomeFunctionType(context.session)) {
                setVariableFunctionReference(node, info)
                return
            }

            if (!varType.hasMustConsumeAnnotation(context.session)) return

            var valueSource = data.getValueSource(node.firstPreviousNode) ?: return

            if (valueSource is ValueSource.QualifiedAccess) {
                 valueSource = valueSource.source
            }

            info.setVarValue(node.fir.symbol, valueSource)
        }

        private fun setVariableFunctionReference(node: VariableDeclarationNode, info: PathInfo) {
            val functionInfo = node.firstPreviousNode.fir.let {
                when (it) {
                    is FirAnonymousFunctionExpression -> data.getLambdaInfo(it.anonymousFunction)

                    is FirCallableReferenceAccess -> {
                        val returnType = it.getReturnType() ?: return@let null

                        FunctionInfo(
                            isLambda = false,
                            returningConsumable = returnType.hasMustConsumeAnnotation(context.session),
                            returnIsConsumed = it.hasDiscardableAnnotation(context.session),
                            consumingThis = it.hasConsumeAnnotation(context.session),
                            consumedParameters = it.getConsumedParameters()
                        )
                    }

                    is FirQualifiedAccessExpression -> {
                        val symbol = it.calleeReference.symbol ?: return
                        info.getVarValue(symbol)?.funcRef
                    }
                    else -> null
                }
            }

            if (functionInfo == null) return

            info.setVarValue(node.fir.symbol, functionInfo)
        }

        private fun handleQualifiedAccess(node: QualifiedAccessNode, info: PathInfo) {
            val varType = node.fir.resolvedType
            if (!varType.hasMustConsumeAnnotation(context.session)) return

            if (node.fir.calleeReference is FirThisReference) {
                data.addValueSource(node, ValueSource.ThisReference(node))
                return
            }

            val symbol = node.fir.calleeReference.symbol ?: return
            val valueSource = info.getVarValue(symbol)?.utilVal ?: return

            data.addValueSource(node, ValueSource.QualifiedAccess(node, symbol, valueSource))
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

        fun log(message: Any? = null) {
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
    private val consumingLambdas : MutableMap<FirDeclaration, FunctionInfo> = mutableMapOf()
    private val variableOwner : MutableMap<FirBasedSymbol<*>, ControlFlowGraph> = mutableMapOf()

    // value sources

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

    // lambda info

    fun addLambdaInfo(func: FirDeclaration, info: FunctionInfo) {
        consumingLambdas[func] = info
    }

    fun getLambdaInfo(func: FirDeclaration) = consumingLambdas[func]

    fun getLambdas() = consumingLambdas

    // owners
    fun setVarOwner(variable: FirBasedSymbol<*>, owner: ControlFlowGraph) {
        variableOwner[variable] = owner
    }

    fun getVarOwner(variable: FirBasedSymbol<*>) = variableOwner[variable]
}

data class FunctionInfo(
    val isLambda: Boolean,
    val returningConsumable : Boolean,
    var returnIsConsumed : Boolean = false,
    var consumingThis : Boolean = false,
    val consumedParameters : MutableSet<Int> = mutableSetOf(),
    val consumedFreeVariables : MutableSet<FirBasedSymbol<*>> = mutableSetOf()
)

fun FunctionInfo.insertThisIntoFirstParameter() : FunctionInfo {
    if (!consumingThis) return this

    val mappedParameters = consumedParameters.map { it + 1 }.toMutableSet()
    mappedParameters.add(0)

    return FunctionInfo(
        isLambda,
        returningConsumable,
        returnIsConsumed,
        consumingThis = false,
        consumedParameters = mappedParameters,
        consumedFreeVariables
    )
}

sealed class VarValue() {
    abstract val funcRef : FunctionInfo?
    abstract val utilVal : ValueSource?

    class FuncValue(private val _funcRef: FunctionInfo) : VarValue() {
        override val funcRef: FunctionInfo
            get() = _funcRef

        override val utilVal: ValueSource?
            get() = null

        override fun equals(other: Any?): Boolean {
            return other is FuncValue && _funcRef == other._funcRef
        }

        override fun hashCode(): Int {
            return _funcRef.hashCode()
        }
    }

    class UtilValue(private val _utilVal: ValueSource) : VarValue() {
        override val funcRef: FunctionInfo?
            get() = null

        override val utilVal: ValueSource
            get() = _utilVal

        override fun equals(other: Any?): Boolean {
            return other is UtilValue && _utilVal == other._utilVal
        }

        override fun hashCode(): Int {
            return _utilVal.hashCode()
        }
    }
}

fun ValueSource.toVarValue() = VarValue.UtilValue(this)
fun FunctionInfo.toVarValue() = VarValue.FuncValue(this)

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

    class QualifiedAccess(node: CFGNode<*>, val symbol: FirBasedSymbol<*>, val source: ValueSource) : ValueSource(node) {
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
    private val knownVariables : MutableMap<FirBasedSymbol<*>, VarValue> = mutableMapOf(),
    private val unutilizedValues : MutableSet<ValueSource> = mutableSetOf()
) {
    fun copy() : PathInfo {
        return PathInfo(knownVariables.toMutableMap(), unutilizedValues.toMutableSet())
    }

    // variables

    fun setVarValue(variable: FirBasedSymbol<*>, valueSource: ValueSource) {
        knownVariables[variable] = valueSource.toVarValue()
    }

    fun setVarValue(variable: FirBasedSymbol<*>, funcRef: FunctionInfo) {
        knownVariables[variable] = funcRef.toVarValue()
    }

    fun getVarValue(variable: FirBasedSymbol<*>) = knownVariables[variable]

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

    fun clearUnutilized() {
        unutilizedValues.clear()
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

private fun FirQualifiedAccessExpression.getConsumedParameters() : MutableSet<Int> {
    val funcSymbol = calleeReference.toResolvedFunctionSymbol() ?: return mutableSetOf()

    return funcSymbol.valueParameterSymbols.asSequence()
        .withIndex()
        .filter { (_, it) ->
            it.containsAnnotation(Utils.Constants.ConsumeClassId)
        }
        .map { (i, _) -> i}
        .toMutableSet()
}