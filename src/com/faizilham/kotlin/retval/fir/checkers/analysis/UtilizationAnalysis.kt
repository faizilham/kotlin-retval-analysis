package com.faizilham.kotlin.retval.fir.checkers.analysis

import com.faizilham.kotlin.retval.fir.checkers.commons.*
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.references.symbol
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.utils.SmartSet

class UtilizationAnalysis(
    private val context: CheckerContext,
    private val funcData: FuncAnalysisData,
    private val logging: Boolean = false
)
{
    private val data = UtilAnalysisData()
    private val warnings: MutableList<FirElement> = mutableListOf()

    fun analyzeGraph(graph: ControlFlowGraph) {
        for (node in graph.nodes) {
            analyzeNode(node)
            (node as? CFGNodeWithSubgraphs<*>)?.subGraphs?.forEach { analyzeGraph(it) }
        }
    }

    fun report(reporter: DiagnosticReporter) {
        for (warnedFir in warnings) {
            reporter.reportOn(warnedFir.source, Commons.Warnings.UNCONSUMED_VALUE, context)
        }
    }

    private fun analyzeNode(node: CFGNode<*>) {
        val info = propagatePathInfo(node)

        when {
            node is FunctionExitNode -> handleFuncExit(node, info)
            node is FunctionCallNode -> handleFuncCall(node, info)
            node is VariableDeclarationNode -> handleVarDeclaration(node, info)
            node is VariableAssignmentNode -> handleVarAssignment(node, info)
            node.isReturnNode() -> handleReturnNode(node, info)
            node.isIndirectValueSource() -> handleIndirectValueSource(node, info)
        }
    }

    /* Function Exit */
    private fun handleFuncExit(node: FunctionExitNode, info: UtilAnalysisPathInfo) {
        // Warnings
        for ((call, util) in info.callSiteUtils) {
            if (!util.leq(UtilLattice.RT)) {
                warnings.add(call.fir)
            }
        }

        // Func Info
        val currentFunction = node.fir

        if (currentFunction is FirAnonymousFunction) {
            data.lambdaFuncInfos[currentFunction] = buildFuncInfo(currentFunction, info)
        }
    }

    private fun buildFuncInfo(func: FirFunction, info: UtilAnalysisPathInfo): FunctionInfo {
        var consumingThis = false
        val consumedParameters = mutableSetOf<Int>()
        val consumedFreeVariables = mutableSetOf<FirBasedSymbol<*>>()

        for ((nonlocal, utilization) in info.nonLocalUtils) {
            if (!utilization.leq(UtilLattice.UT)) continue

            when (nonlocal) {
                is ValueSource.ThisRef -> consumingThis = true
                is ValueSource.Params -> consumedParameters.add(nonlocal.index)
                is ValueSource.FreeVar -> consumedFreeVariables.add(nonlocal.symbol)
            }
        }

        val returningConsumable = isUtilizableType(func.returnTypeRef.coneType)
        val isExtension = func.receiverParameter != null

        return FunctionInfo(
            isLambda = true,
            isExtension,
            returningConsumable,
            returnIsConsumed = false,
            consumingThis,
            consumedParameters,
            consumedFreeVariables
        )
    }

    /* Function Call */

    private fun handleFuncCall(node: FunctionCallNode, info: UtilAnalysisPathInfo) {
        val funcInfo = getFunctionInfo(node) ?: return
        if (funcInfo.hasNoEffect()) return

        consumeReceiver(node, info, funcInfo)
        consumeParameters(node, info, funcInfo)
        consumeFreeVariables(node, info, funcInfo)

        if (funcInfo.returningConsumable && !funcInfo.returnIsConsumed) {
            val source = ValueSource.CallSite(node.fir)
            info.callSiteUtils[source] = UtilLattice.Top
        }
    }

    private fun consumeReceiver(node: FunctionCallNode, info: UtilAnalysisPathInfo, funcInfo: FunctionInfo) {
        if (!funcInfo.consumingThis) return

        val receiver = node.fir.dispatchReceiver ?: node.fir.extensionReceiver ?: return
        val resolvedReceiver = resolve(receiver, node, info, true)

        markUtilized(resolvedReceiver, info)
    }

    private fun consumeParameters(node: FunctionCallNode, info: UtilAnalysisPathInfo, funcInfo: FunctionInfo) {
        for (consumedId in funcInfo.consumedParameters) {
            val arg = node.fir.argumentList.arguments.getOrNull(consumedId) ?: continue
            val argSources = resolve(arg, node, info, true)

            markUtilized(argSources, info)
        }
    }

    private fun consumeFreeVariables(node: FunctionCallNode, info: UtilAnalysisPathInfo, funcInfo: FunctionInfo) {
        for (freeVar in funcInfo.consumedFreeVariables) {
            val sources = resolveVar(freeVar, node, info, true)
            markUtilized(sources, info)
        }
    }

    private fun markUtilized(valueSources: SetLat<ValueSource>, info: UtilAnalysisPathInfo) {
        for (source in valueSources) {
            when(source) {
                is ValueSource.NonLocalSource -> info.nonLocalUtils.meetVal(source, UtilLattice.UT)
                is ValueSource.CallSite -> info.callSiteUtils.meetVal(source, UtilLattice.UT)
                is ValueSource.TransientVar -> {}
            }
        }
    }

    // Function Info Resolving

    private fun getFunctionInfo(node: FunctionCallNode) : FunctionInfo? {
        val fir = node.fir
        if (fir.isInvoke()) return resolveInvokeFunctionInfo(node)

        return FunctionInfo(
            isLambda = false,
            isClassMemberOrExtension = fir.isClassMemberOrExtension(),
            returningConsumable = isUtilizableType(fir.resolvedType),
            returnIsConsumed = fir.hasDiscardableAnnotation(context.session),
            consumingThis = fir.hasConsumeAnnotation(context.session),
            consumedParameters = fir.getConsumedParameters()
        )
    }

    private fun resolveInvokeFunctionInfo(node: FunctionCallNode) : FunctionInfo? {
        val originalRef = (node.fir.dispatchReceiver as? FirQualifiedAccessExpression)?.calleeReference ?: return null
        val originalSymbol = originalRef.toResolvedCallableSymbol() ?: return null

        val funcRef = funcData.pathInfos[node]?.getVarValue(originalSymbol) ?: return null
        var funcInfo = getFuncRefInfo(funcRef) ?: return null

        // NOTE: invoking extension function causes the context object to be regarded as first argument
        //       the dispatchReceiver is no longer the context object, but the function reference
        if (funcInfo.isClassMemberOrExtension) {
            funcInfo = funcInfo.convertThisToFirstParameter()
        }

        return funcInfo
    }

    private fun getFuncRefInfo(funcRef: FuncRefValue): FunctionInfo? {
        return when (funcRef) {
            is FuncRefValue.LambdaRef -> data.lambdaFuncInfos[funcRef.lambda]
            is FuncRefValue.CallableRef -> {
                val ref = funcRef.ref
                val returnType = ref.getReturnType() ?: return null

                FunctionInfo(
                    isLambda = false,
                    isClassMemberOrExtension = ref.isClassMemberOrExtension(),
                    returningConsumable = isUtilizableType(returnType),
                    returnIsConsumed = ref.hasDiscardableAnnotation(context.session),
                    consumingThis = ref.hasConsumeAnnotation(context.session),
                    consumedParameters = ref.getConsumedParameters()
                )
            }
            else -> null
        }
    }

    /* Variable aliasing */

    private fun handleVarDeclaration(node: VariableDeclarationNode, info: UtilAnalysisPathInfo) {
        val varType = node.fir.returnTypeRef.coneType
        if (!isUtilizableType(varType)) return

        val varRef = ValueRef.Variable(node.fir.symbol, VariableRecord(node.owner, true))

        info.reachingValues[varRef] = resolve(node.fir.initializer, node, info, false)
    }

    private fun handleVarAssignment(node: VariableAssignmentNode, info: UtilAnalysisPathInfo) {
        val varType = node.fir.lValue.resolvedType
        if (!isUtilizableType(varType)) return

        val symbol = node.fir.calleeReference?.symbol ?: return
        val record = funcData.variableRecords[symbol] ?: return

        val varRef = ValueRef.Variable(symbol, record)

        info.occludedSources.joinVal(varRef, getOccluded(info.reachingValues[varRef]))
        info.reachingValues[varRef] = resolve(node.fir.rValue, node, info, false) // TODO: solve cyclic assignment?
    }

    /* returns and other nodes */

    private fun handleReturnNode(node: CFGNode<*>, info: UtilAnalysisPathInfo) {
        val retTarget = node.firstPreviousNode.fir
        if (retTarget !is FirExpression) return

        val resolvedTargets = resolve(retTarget, node, info, true)

        for (source in resolvedTargets) {
            if (source is ValueSource.CallSite) {
                info.callSiteUtils.meetVal(source, UtilLattice.UT)
            }
        }
    }

    private fun handleIndirectValueSource(node: CFGNode<*>, info: UtilAnalysisPathInfo) {
        val firExpr = when(val fir = node.fir) {
            is FirExpression -> fir
            is FirWhenBranch -> fir.result
            else -> return
        }

        if (!isUtilizableType(firExpr.resolvedType)) return

        val combined = SmartSet.create<ValueSource>()

        for (prev in node.previousNodes) {
            val prevSources = resolveNode(prev, info, false)
            combined.addAll(prevSources)
        }

        info.reachingValues[ValueRef.Expr(firExpr)] = SetLat.from(combined)
    }


    // resolving

    private fun resolveNode(node: CFGNode<*>, info: UtilAnalysisPathInfo, deepResolve: Boolean): SetLat<ValueSource> {
        return when (val fir = node.fir) {
            is FirExpression -> resolve(fir, node, info, deepResolve)
            is FirWhenBranch -> resolve(fir.result, node, info, deepResolve)
            else -> SetLat()
        }
    }

    private fun resolve(expr: FirExpression?, caller: CFGNode<*>, info: UtilAnalysisPathInfo, deepResolve: Boolean): SetLat<ValueSource> {
        if (expr == null || !isUtilizableType(expr.resolvedType)) {
            return emptySource
        }

        if (expr is FirFunctionCall) return SetLat(ValueSource.CallSite(expr))

        if (expr !is FirQualifiedAccessExpression) {
            return resolveRef(ValueRef.Expr(expr), caller, info, deepResolve)
        }

        if (expr.calleeReference is FirThisReference) return SetLat(ValueSource.ThisRef)

        return resolveVar(expr.calleeReference.symbol, caller, info, deepResolve)
    }

    private fun resolveVar(symbol: FirBasedSymbol<*>?, caller: CFGNode<*>, info: UtilAnalysisPathInfo, deepResolve: Boolean): SetLat<ValueSource> {
        if (symbol == null) return emptySource

        val records = funcData.variableRecords[symbol] ?: return emptySource
        return resolveRef(ValueRef.Variable(symbol, records), caller, info, deepResolve)
    }

    private fun resolveRef(valueRef: ValueRef, caller: CFGNode<*>, info: UtilAnalysisPathInfo, deepResolve: Boolean) : SetLat<ValueSource> {
        if(!deepResolve && valueRef is ValueRef.Variable) {
            return SetLat(ValueSource.TransientVar(valueRef.symbol, valueRef.record, caller))
        }

        val reaching = info.reachingValues.getWithDefault(valueRef)

        if (reaching.isEmpty()) {
            if (valueRef !is ValueRef.Variable)
                return emptySource
            if (valueRef.record.owner != caller.owner)
                return SetLat(ValueSource.FreeVar(valueRef.symbol))
            if (valueRef.record.paramIndex >= 0)
                return SetLat(ValueSource.Params(valueRef.symbol, valueRef.record.paramIndex))

            return emptySource
        }

        if (reaching.size > 1) {
            val occluded = info.occludedSources.getWithDefault(valueRef)
            return SetLat.from(reaching.filter { it is ValueSource.CallSite && it !in occluded })
        }

        val source = reaching.first()
        if (!deepResolve || source !is ValueSource.TransientVar) return reaching

        if (valueRef is ValueRef.Variable && valueRef.symbol == source.symbol && caller == source.accessAt) {
            // NOTE: should not be possible, but just in case
            return emptySource
        }

        val sourceInfo = data.pathInfos[source.accessAt] ?: return emptySource
        return resolveRef(ValueRef.Variable(source.symbol, source.record), source.accessAt, sourceInfo, true)
    }

    private fun getOccluded(valueSources: SetLat<ValueSource>?): SetLat<ValueSource.CallSite> {
        if (valueSources == null) return emptyCallsites

        val occluded = SmartSet.create<ValueSource.CallSite>()

        for (source in valueSources) {
            if (source is ValueSource.CallSite) {
                occluded.add(source)
            }
        }

        return SetLat.from(occluded)
    }

    private val emptySource = SetLat<ValueSource>()
    private val emptyCallsites = SetLat<ValueSource.CallSite>()

    private fun isUtilizableType(type: ConeKotlinType) : Boolean {
        return type.hasMustConsumeAnnotation(context.session)
    }

    // Path Info

    private fun propagatePathInfo(node: CFGNode<*>) : UtilAnalysisPathInfo {
        if (node is FunctionEnterNode)
            return UtilAnalysisPathInfo()

        val pathInfos = node.previousNodes.asSequence()
            .filterNot { it.isInvalidPrev(node) || it is FunctionExitNode }
            .mapNotNull { data.pathInfos[it] }
            .toList()

        val info = pathInfos.mergeAll(true) ?: UtilAnalysisPathInfo()

        data.pathInfos[node] = info

        return info
    }

    private fun log(message: Any?) {
        if (logging) println(message)
    }
}

private class UtilAnalysisData {
    val pathInfos: MutableMap<CFGNode<*>, UtilAnalysisPathInfo> = mutableMapOf()
    val lambdaFuncInfos: MutableMap<FirAnonymousFunction, FunctionInfo> = mutableMapOf()
}

private class UtilAnalysisPathInfo private constructor(
    val reachingValues : DefaultMapLat<ValueRef, SetLat<ValueSource>>,
    val occludedSources: DefaultMapLat<ValueRef, SetLat<ValueSource.CallSite>>,
    val callSiteUtils: DefaultMapLat<ValueSource.CallSite, UtilLattice>,
    val nonLocalUtils: DefaultMapLat<ValueSource.NonLocalSource, UtilLattice>
) : PathInfo<UtilAnalysisPathInfo> {
    constructor() : this(
        DefaultMapLat(SetLat()),
        DefaultMapLat(SetLat()),
        DefaultMapLat(UtilLattice.Bot),
        DefaultMapLat(UtilLattice.Top)
    )

    override fun copy(): UtilAnalysisPathInfo {
        return UtilAnalysisPathInfo(
            reachingValues.copy(),
            occludedSources.copy(),
            callSiteUtils.copy(),
            nonLocalUtils.copy()
        )
    }

    override fun merge(other: UtilAnalysisPathInfo): UtilAnalysisPathInfo {
        return UtilAnalysisPathInfo(
            reachingValues.merge(other.reachingValues),
            occludedSources.merge(other.occludedSources),
            callSiteUtils.merge(other.callSiteUtils),
            nonLocalUtils.merge(other.nonLocalUtils)
        )
    }
}

// Lattices

private sealed interface ValueRef{
    data class Expr(val fir: FirExpression) : ValueRef
    data class Variable(val symbol: FirBasedSymbol<*>, val record: VariableRecord): ValueRef
}

private sealed interface ValueSource {
    data class TransientVar(val symbol: FirBasedSymbol<*>, val record: VariableRecord, val accessAt: CFGNode<*>): ValueSource

    data class CallSite(val fir: FirFunctionCall) : ValueSource

    sealed interface NonLocalSource : ValueSource

    data object ThisRef : NonLocalSource
    data class Params(val symbol: FirBasedSymbol<*>, val index: Int): NonLocalSource
    data class FreeVar(val symbol: FirBasedSymbol<*>): NonLocalSource
}

private sealed class UtilLattice(private val value: Int): Lattice<UtilLattice> {
    data object Top: UtilLattice(3)
    data object RT: UtilLattice(2)
    data object UT: UtilLattice(1)
    data object Bot: UtilLattice(0)

    fun leq(other: UtilLattice) = value <= other.value

    fun geq(other: UtilLattice) = value >= other.value

    override fun join(other: UtilLattice) = if (this.geq(other)) this else other

    override fun meet(other: UtilLattice) = if (this.geq(other)) other else this
}
